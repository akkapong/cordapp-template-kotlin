package com.template.flows

import co.paralleluniverse.fibers.Suspendable
import com.r3.corda.lib.tokens.contracts.types.TokenPointer
import com.r3.corda.lib.tokens.contracts.utilities.heldBy
import com.r3.corda.lib.tokens.contracts.utilities.issuedBy
import com.r3.corda.lib.tokens.contracts.utilities.of
import com.r3.corda.lib.tokens.workflows.flows.move.addMoveFungibleTokens
import com.r3.corda.lib.tokens.workflows.internal.flows.distribution.UpdateDistributionListFlow
import com.r3.corda.lib.tokens.workflows.internal.flows.finality.ObserverAwareFinalityFlow
import com.r3.corda.lib.tokens.workflows.internal.flows.finality.ObserverAwareFinalityFlowHandler
import com.r3.corda.lib.tokens.workflows.types.PartyAndAmount
import com.r3.corda.lib.tokens.workflows.utilities.getPreferredNotary
import com.template.contracts.AccountContract
import com.template.schemas.AccountSchema
import com.template.states.Account
import com.template.states.DVToken
import com.template.utilities.Conditions.using
import com.template.utilities.getAllParties
import net.corda.core.contracts.Command
import net.corda.core.contracts.StateAndRef
import net.corda.core.contracts.requireThat
import net.corda.core.flows.*
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.node.services.Vault
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.*
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.ProgressTracker.Step
import java.math.BigDecimal

object TransferDVToken {
    @InitiatingFlow
    @StartableByRPC
    class Initiator(val fromAccount: String,
                    val toAccount: String,
                    val amount: BigDecimal) : FlowLogic<SignedTransaction>() {

        companion object {
            object GENERATING_TRANSACTION : Step("Generating transaction based on new Account.")
            object VERIFYING_TRANSACTION : Step("Verifying contract constraints.")
            object SIGNING_TRANSACTION : Step("Signing transaction with our private key.")
            object GATHERING_SIGS : Step("Gathering the counterparty's signature.") {
                override fun childProgressTracker() = CollectSignaturesFlow.tracker()
            }

            object FINALISING_TRANSACTION : Step("Obtaining notary signature and recording transaction.") {
                override fun childProgressTracker() = FinalityFlow.tracker()
            }

            fun tracker() = ProgressTracker(
                    GENERATING_TRANSACTION,
                    VERIFYING_TRANSACTION,
                    SIGNING_TRANSACTION,
                    GATHERING_SIGS,
                    FINALISING_TRANSACTION
            )
        }

        override val progressTracker = tracker()

        private val involvedAccountsMap = mutableMapOf<String, StateAndRef<Account>>()

        private lateinit var issuer: Party

        private val NODE = "NODE"
        /**
         * The flow logic is encapsulated within the call() method.
         */
        @Suspendable
        override fun call(): SignedTransaction {
            // Obtain a reference to the notary we want to use.
            issuer = serviceHub.identityService.wellKnownPartyFromX500Name(CordaX500Name.parse("O=PartyA,L=London,C=GB"))!!

            inspect()

            // Stage 1.
            progressTracker.currentStep = GENERATING_TRANSACTION
            // We can specify preferred notary in cordapp config file, otherwise the first one from network parameters is chosen.
            val txBuilder = TransactionBuilder(notary = getPreferredNotary(serviceHub))

            // If from account is null or empty we will transfer amount from node to account
            when (fromAccount == NODE) {
                true -> transferFromNodeToAccount(txBuilder)
                false -> transferFromAccountToAccount(txBuilder)
            }

            logger.info("Transaction before sign txBuilder: $txBuilder")

            // Stage 2.
            progressTracker.currentStep = VERIFYING_TRANSACTION
            // Verify that the transaction is valid.
            txBuilder.verify(serviceHub)

            // Stage 3.
            progressTracker.currentStep = SIGNING_TRANSACTION
            // Sign the transaction.
            val partSignedTx = serviceHub.signInitialTransaction(txBuilder)

            val otherAbstractParty = getAllParties(serviceHub).filterNot { it == ourIdentity }.single()

            val otherParty = serviceHub.identityService.wellKnownPartyFromAnonymous(otherAbstractParty)!!
            // Stage 4.
            progressTracker.currentStep = GATHERING_SIGS
            // Send the state to the counterparty, and receive it back with their signature.
            val otherPartySession = initiateFlow(otherParty)
            val fullySignedTx = subFlow(CollectSignaturesFlow(partSignedTx, setOf(otherPartySession), GATHERING_SIGS.childProgressTracker()))


            // Update distribution list.
            subFlow(UpdateDistributionListFlow(fullySignedTx))

            // Stage 5.
            progressTracker.currentStep = FINALISING_TRANSACTION
            return subFlow(ObserverAwareFinalityFlow(fullySignedTx, listOf(otherPartySession)))

//            // Notarise and record the transaction in both parties' vaults.
//            return subFlow(FinalityFlow(fullySignedTx, setOf(otherPartySession), FINALISING_TRANSACTION.childProgressTracker()))
        }

        fun inspect() {
            val involvedAccounts = listOf(fromAccount, toAccount).filterNot { it == NODE  }
            val existingAccounts = getExistingAccountByName(involvedAccounts)


            "Account must be existing." using (existingAccounts.size == involvedAccounts.size)

            existingAccounts.map {
                val account = it.state.data
                involvedAccountsMap.put(account.accountName, it)
            }

            "Destination must not be empty." using (toAccount.isNotEmpty() && involvedAccountsMap[toAccount] != null)

            if (fromAccount != NODE) {
                // Check available amount
                val senderAccount = involvedAccountsMap[fromAccount]!!.state.data
                val senderAmount = senderAccount.amount?.amount?.toDecimal() ?: BigDecimal.ZERO
                "Sender must have enough token for transfer." using (senderAmount >= amount)
            }

            // TODO: check amount of node

        }

        /**
         * Method for create transaction that include
         * 1. move token to node
         * 2. update receiver account
         */
        private fun transferFromNodeToAccount(txBuilder: TransactionBuilder) {
            val destinationAccountIn = involvedAccountsMap[toAccount]!!
            val destinationNode = destinationAccountIn.state.data.ourParty

            // Move token to destination
            val dvPtr = getDvTokenPointer()

            // Move token to another node
            moveTokenToAnotherNode(
                    txBuilder = txBuilder,
                    destinationNode = destinationNode,
                    dvPtr = dvPtr
            )

            // Update amount in receiver account
            updateReceiverAmount(
                    txBuilder = txBuilder,
                    destinationAccountIn = destinationAccountIn,
                    dvPtr = dvPtr
            )

        }

        /**
         * Method for create transaction that include
         *  1 move token to same node
         *  2 increase amount for receiver
         *  3 decrease amount for sender
         *
         */
        private fun transferFromAccountToAccount(txBuilder: TransactionBuilder) {
            val destinationAccountIn = involvedAccountsMap[toAccount]!!
            val destinationNode = destinationAccountIn.state.data.ourParty

            val senderAccountIn = involvedAccountsMap[toAccount]!!

            // Move token to destination
            val dvPtr = getDvTokenPointer()

            // Move token to another node
            moveTokenToAnotherNode(
                    txBuilder = txBuilder,
                    destinationNode = destinationNode,
                    dvPtr = dvPtr
            )

            // Update amount in receiver account
            updateReceiverAmount(
                    txBuilder = txBuilder,
                    destinationAccountIn = destinationAccountIn,
                    dvPtr = dvPtr
            )

            // Update amount in receiver account
            updateSenderAmount(
                    txBuilder = txBuilder,
                    senderAccountIn = senderAccountIn,
                    dvPtr = dvPtr
            )
        }

        /**
         * Method for move the token to another node
         * we will check destination input not be our node before add to the transaction
         * if destination is same out node no need to do anything
         */
        private fun moveTokenToAnotherNode(txBuilder: TransactionBuilder, destinationNode: Party, dvPtr: TokenPointer<DVToken>) {
            // Check account in our node ?
            if (destinationNode != ourIdentity) {

                val amountToken = amount of dvPtr
                val changeHolder = serviceHub.keyManagementService.freshKeyAndCert(ourIdentityAndCert, false).party.anonymise()
                addMoveFungibleTokens(
                        transactionBuilder = txBuilder,
                        serviceHub = serviceHub,
                        partiesAndAmounts = listOf(PartyAndAmount(destinationNode, amountToken)),
                        changeHolder = changeHolder
                )

            }
        }

        /**
         * Method for update amount of receiver
         * Increase amount value
         */
        private fun updateReceiverAmount(txBuilder: TransactionBuilder,
                                         destinationAccountIn: StateAndRef<Account>,
                                         dvPtr: TokenPointer<DVToken>) {

            val destinationNode = destinationAccountIn.state.data.ourParty
            // Update amount in receiver account
            val updatedAmount = ((destinationAccountIn.state.data.amount?.amount?.toDecimal()?: BigDecimal.ZERO) +
                    amount) of dvPtr issuedBy issuer heldBy destinationNode

            val destinationAccountOut = destinationAccountIn.state.data.copy(
                    amount = updatedAmount
            )

            val txCommand = Command(AccountContract.Commands.UpdateReceiverAmount(), destinationAccountOut.participants.map { it.owningKey })

            // update account amount
            txBuilder.apply {
                addInputState(destinationAccountIn)
                addOutputState(destinationAccountOut, AccountContract.ACCOUNT_CONTRACT_ID)
                addCommand(txCommand)
            }
        }

        /**
         * Method for update amount of sender
         * Decrease amount value
         */
        private fun updateSenderAmount(txBuilder: TransactionBuilder,
                                       senderAccountIn: StateAndRef<Account>,
                                       dvPtr: TokenPointer<DVToken>) {

            val senderNode = senderAccountIn.state.data.ourParty
            // update amount in sender account
            val updatedAmount = ((senderAccountIn.state.data.amount?.amount?.toDecimal()?: BigDecimal.ZERO) -
                    amount) of dvPtr issuedBy issuer heldBy senderNode

            val senderAccountOut = senderAccountIn.state.data.copy(
                    amount = updatedAmount
            )

            val txCommand = Command(AccountContract.Commands.UpdateSenderAmount(), senderAccountOut.participants.map { it.owningKey })

            // update account amount
            txBuilder.apply {
                addInputState(senderAccountIn)
                addOutputState(senderAccountOut, AccountContract.ACCOUNT_CONTRACT_ID)
                addCommand(txCommand)
            }
        }

        private fun getDvTokenPointer(): TokenPointer<DVToken> {
            val queryCriteria = QueryCriteria.LinearStateQueryCriteria()
            val dvToken = serviceHub.vaultService.queryBy<DVToken>(queryCriteria).states.single().state.data

            return dvToken.toPointer()
        }


        private fun getExistingAccountByName(accountsName: List<String>): List<StateAndRef<Account>> {
            val generalCriteria = QueryCriteria.VaultQueryCriteria(Vault.StateStatus.UNCONSUMED)
            val linearCriteria = QueryCriteria.LinearStateQueryCriteria()
            val customExpression = builder { AccountSchema.PersistentAccount::accountName.`in`(accountsName) }
            val customCriteria = QueryCriteria.VaultCustomQueryCriteria(customExpression)

            val queryCriteria = linearCriteria
                    .and(generalCriteria)
                    .and(customCriteria)


            return serviceHub.vaultService.queryBy<Account>(
                    criteria = queryCriteria,
                    paging = PageSpecification(DEFAULT_PAGE_NUM, MAX_PAGE_SIZE)).states
        }

    }

    @InitiatedBy(Initiator::class)
    class Acceptor(val otherPartySession: FlowSession) : FlowLogic<Unit>() {
        @Suspendable
        override fun call(): Unit {
            val signTransactionFlow = object : SignTransactionFlow(otherPartySession) {
                override fun checkTransaction(stx: SignedTransaction) = requireThat {
                    val account = stx.tx.outputsOfType<Account>().single()
                    "This must be an Account transaction." using (account is Account)
                }
            }

            return subFlow(ObserverAwareFinalityFlowHandler(otherPartySession))
        }
    }
}