package com.template.flows

import co.paralleluniverse.fibers.Suspendable
import com.r3.corda.lib.tokens.contracts.states.EvolvableTokenType
import com.r3.corda.lib.tokens.contracts.states.FungibleToken
import com.r3.corda.lib.tokens.contracts.states.NonFungibleToken
import com.r3.corda.lib.tokens.contracts.types.TokenType
import com.r3.corda.lib.tokens.contracts.utilities.issuedBy
import com.r3.corda.lib.tokens.money.FiatCurrency
import com.r3.corda.lib.tokens.workflows.flows.move.addMoveFungibleTokens
import com.r3.corda.lib.tokens.workflows.flows.move.addMoveNonFungibleTokens
import com.r3.corda.lib.tokens.workflows.flows.move.addMoveTokens
import com.r3.corda.lib.tokens.workflows.internal.flows.confidential.RequestConfidentialIdentityFlow
import com.r3.corda.lib.tokens.workflows.internal.flows.confidential.RequestConfidentialIdentityFlowHandler
import com.r3.corda.lib.tokens.workflows.internal.flows.distribution.UpdateDistributionListFlow
import com.r3.corda.lib.tokens.workflows.internal.flows.finality.ObserverAwareFinalityFlow
import com.r3.corda.lib.tokens.workflows.internal.flows.finality.ObserverAwareFinalityFlowHandler
import com.r3.corda.lib.tokens.workflows.internal.selection.TokenSelection
import com.r3.corda.lib.tokens.workflows.types.PartyAndAmount
import com.r3.corda.lib.tokens.workflows.types.PartyAndToken
import com.r3.corda.lib.tokens.workflows.utilities.*
import com.template.states.AKKTokenType
import net.corda.confidential.IdentitySyncFlow
import net.corda.core.contracts.Amount
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.flows.*
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import net.corda.core.identity.PartyAndCertificate
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.serialization.CordaSerializable
import net.corda.core.transactions.LedgerTransaction
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.unwrap
import java.util.*

object SellAkkToken {

    @StartableByRPC
    @InitiatingFlow
    class Initiator(val akkLinearId: String, val newHolder: Party) : FlowLogic<SignedTransaction>() {
        @Suspendable
        override fun call(): SignedTransaction {

            val queryCriteria = QueryCriteria.LinearStateQueryCriteria(uuid = listOf(UUID.fromString(akkLinearId)))
            val akkToken = serviceHub.vaultService.queryBy<AKKTokenType>(queryCriteria).states.single().state.data

            val akkPtr = akkToken.toPointer<AKKTokenType>()

            // We can specify preferred notary in cordapp config file, otherwise the first one from network parameters is chosen.
            val txBuilder = TransactionBuilder(notary = getPreferredNotary(serviceHub))
            addMoveNonFungibleTokens(txBuilder, serviceHub, akkPtr, newHolder)

            // Initiate new flow session. If this flow is supposed to be called as inline flow, then session should have been already passed.
            val session = initiateFlow(newHolder)
            // Ask for input stateAndRefs - send notification with the amount to exchange.
            session.send(PriceNotification(akkToken.valuation))
            // Receive GBP states back.
            val inputs = subFlow(ReceiveStateAndRefFlow<FungibleToken>(session))
            // Receive outputs.
            val outputs = session.receive<List<FungibleToken>>().unwrap { it }
            // For the future we could add some checks for inputs and outputs - that they sum up to house valuation,
            // usually we would like to implement it as part of the contract
            addMoveTokens(txBuilder, inputs, outputs)
            logger.info("txBuilder : ${txBuilder.toLedgerTransaction(serviceHub)}")

            // Synchronise any confidential identities
            subFlow(IdentitySyncFlow.Send(session, txBuilder.toWireTransaction(serviceHub)))
            val ourSigningKeys = txBuilder.toLedgerTransaction(serviceHub).ourSigningKeys(serviceHub)
            val initialStx = serviceHub.signInitialTransaction(txBuilder, signingPubKeys = ourSigningKeys)
            val stx = subFlow(CollectSignaturesFlow(initialStx, listOf(session), ourSigningKeys))
            // Update distribution list.
            subFlow(UpdateDistributionListFlow(stx))

            return subFlow(ObserverAwareFinalityFlow(stx, listOf(session)))
        }
    }

    @InitiatedBy(SellAkkToken.Initiator::class)
    class Responder(val otherSession: FlowSession) : FlowLogic<Unit>() {
        @Suspendable
        override fun call() {
            // Receive notification with house price.
            val priceNotification = otherSession.receive<PriceNotification>().unwrap { it }
            // Generate fresh key, possible change outputs will belong to this key.
            val changeHolder = serviceHub.keyManagementService.freshKeyAndCert(ourIdentityAndCert, false).party.anonymise()
            // Chose state and refs to send back.
            val (inputs, outputs) = TokenSelection(serviceHub).generateMove(
                    lockId = runId.uuid,
                    partyAndAmounts = listOf(PartyAndAmount(otherSession.counterparty, priceNotification.amount)),
                    changeHolder = changeHolder
            )
            subFlow(SendStateAndRefFlow(otherSession, inputs))
            otherSession.send(outputs)
            subFlow(IdentitySyncFlow.Receive(otherSession))
            subFlow(object : SignTransactionFlow(otherSession) {
                override fun checkTransaction(stx: SignedTransaction) {
                    // We should perform some basic sanity checks before signing the transaction. This step was omitted for simplicity.
                }
            })
            //subFlow(RequestConfidentialIdentityFlowHandler(otherSession))
            subFlow(ObserverAwareFinalityFlowHandler(otherSession))
        }
    }
}

@CordaSerializable
data class PriceNotification(val amount: Amount<TokenType>)
