package com.template.flows

import co.paralleluniverse.fibers.Suspendable
import com.r3.corda.lib.tokens.contracts.states.FungibleToken
import com.r3.corda.lib.tokens.contracts.utilities.heldBy
import com.r3.corda.lib.tokens.contracts.utilities.issuedBy
import com.r3.corda.lib.tokens.contracts.utilities.of
import com.r3.corda.lib.tokens.contracts.utilities.withNotary
import com.r3.corda.lib.tokens.workflows.flows.rpc.ConfidentialIssueTokens
import com.r3.corda.lib.tokens.workflows.flows.rpc.CreateEvolvableTokens
import com.r3.corda.lib.tokens.workflows.utilities.getPreferredNotary
import com.r3.corda.lib.tokens.workflows.utilities.heldBy
import com.template.contracts.AccountContract
import com.template.schemas.AccountSchema
import com.template.states.AKKTokenType
import com.template.states.Account
import com.template.states.DVToken
import net.corda.core.flows.*
import net.corda.core.identity.CordaX500Name
import net.corda.core.node.services.Vault
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.*
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.ProgressTracker.Step
import com.template.utilities.Conditions.using
import net.corda.core.contracts.Command
import net.corda.core.contracts.requireThat
import net.corda.core.identity.Party
import net.corda.core.transactions.TransactionBuilder
import java.math.BigDecimal
import java.time.Instant

object CreateAccountFlow {
    @InitiatingFlow
    @StartableByRPC
    class Initiator(val accountName: String,
                    val otherParty: Party,
                    val amount: BigDecimal) : FlowLogic<SignedTransaction>() {
        /**
         * The progress tracker checkpoints each stage of the flow and outputs the specified messages when each
         * checkpoint is reached in the code. See the 'progressTracker.currentStep' expressions within the call() function.
         */
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

        /**
         * The flow logic is encapsulated within the call() method.
         */
        @Suspendable
        override fun call(): SignedTransaction {
            // Obtain a reference to the notary we want to use.
            val notary = serviceHub.networkMapCache.notaryIdentities[0]

            inspect()



            // Stage 1.
            progressTracker.currentStep = GENERATING_TRANSACTION
            // Generate an unsigned transaction.
            val accountState = Account(
                    accountName = accountName,
                    ourParty = ourIdentity,
                    otherParty = otherParty,
                    amount = crateToken()
            )
            val txCommand = Command(AccountContract.Commands.Create(), accountState.participants.map { it.owningKey })
            val txBuilder = TransactionBuilder(notary)
                    .addOutputState(accountState, AccountContract.ACCOUNT_CONTRACT_ID)
                    .addCommand(txCommand)

            // Stage 2.
            progressTracker.currentStep = VERIFYING_TRANSACTION
            // Verify that the transaction is valid.
            txBuilder.verify(serviceHub)

            // Stage 3.
            progressTracker.currentStep = SIGNING_TRANSACTION
            // Sign the transaction.
            val partSignedTx = serviceHub.signInitialTransaction(txBuilder)

            val otherAbstractParty = accountState.participants.filterNot { it == ourIdentity }.single()

            val otherParty = serviceHub.identityService.wellKnownPartyFromAnonymous(otherAbstractParty)!!
            // Stage 4.
            progressTracker.currentStep = GATHERING_SIGS
            // Send the state to the counterparty, and receive it back with their signature.
            val otherPartySession = initiateFlow(otherParty)
            val fullySignedTx = subFlow(CollectSignaturesFlow(partSignedTx, setOf(otherPartySession), GATHERING_SIGS.childProgressTracker()))

            // Stage 5.
            progressTracker.currentStep = FINALISING_TRANSACTION
            // Notarise and record the transaction in both parties' vaults.
            return subFlow(FinalityFlow(fullySignedTx, setOf(otherPartySession), FINALISING_TRANSACTION.childProgressTracker()))
        }

        fun inspect() {
            val existingAccounts = getExistingAccountByName(accountName)

            "Duplicate account name." using (existingAccounts.isEmpty())
        }

        private fun getExistingAccountByName(accountName: String): List<Account> {
            val generalCriteria = QueryCriteria.VaultQueryCriteria(Vault.StateStatus.UNCONSUMED)
            val linearCriteria = QueryCriteria.LinearStateQueryCriteria()
            val customExpression = builder { AccountSchema.PersistentAccount::accountName.equal(accountName) }
            val customCriteria = QueryCriteria.VaultCustomQueryCriteria(customExpression)

            val queryCriteria = linearCriteria
                    .and(generalCriteria)
                    .and(customCriteria)


            return serviceHub.vaultService.queryBy<Account>(
                    criteria = queryCriteria,
                    paging = PageSpecification(DEFAULT_PAGE_NUM, MAX_PAGE_SIZE)).states.map { it.state.data }
        }

        @Suspendable
        private fun crateToken(): FungibleToken {
            // From within the flow.
            val dvTokenType = DVToken(
                    date = Instant.now(),
                    maintainers = listOf(ourIdentity, otherParty),
                    fractionDigits = 2

            )

            val notary: Party = getPreferredNotary(serviceHub) // Or provide notary party using your favourite function from NotaryUtilities.
            // We need to create the evolvable token first.
            subFlow(CreateEvolvableTokens(dvTokenType withNotary notary))

            val dvPtr = dvTokenType.toPointer<DVToken>()

            // Create FungibleToken
            val issuedToken = amount of dvPtr issuedBy ourIdentity heldBy otherParty
            subFlow(ConfidentialIssueTokens(listOf(issuedToken)))

            return issuedToken
        }


    }

    @InitiatedBy(Initiator::class)
    class Acceptor(val otherPartySession: FlowSession) : FlowLogic<SignedTransaction>() {
        @Suspendable
        override fun call(): SignedTransaction {
            val signTransactionFlow = object : SignTransactionFlow(otherPartySession) {
                override fun checkTransaction(stx: SignedTransaction) = requireThat {
                    val output = stx.tx.outputs.single().data
                    "This must be an Account transaction." using (output is Account)
                }
            }
            val txId = subFlow(signTransactionFlow).id

            return subFlow(ReceiveFinalityFlow(otherPartySession, expectedTxId = txId))
        }
    }
}
