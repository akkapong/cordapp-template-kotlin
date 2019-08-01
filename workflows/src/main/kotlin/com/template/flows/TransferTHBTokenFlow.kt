package com.template.flows

import co.paralleluniverse.fibers.Suspendable
import com.r3.corda.lib.tokens.contracts.utilities.of
import com.r3.corda.lib.tokens.workflows.flows.move.MoveFungibleTokensFlow
import com.r3.corda.lib.tokens.workflows.flows.move.MoveTokensFlow
import com.r3.corda.lib.tokens.workflows.flows.move.MoveTokensFlowHandler
import com.r3.corda.lib.tokens.workflows.flows.rpc.MoveFungibleTokens
import com.r3.corda.lib.tokens.workflows.types.PartyAndAmount
import com.r3.corda.lib.tokens.workflows.utilities.sessionsForParties
import com.template.utilities.Conditions.using
import com.template.utilities.getMyToken
import net.corda.core.flows.*
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.ProgressTracker
import java.math.BigDecimal

@StartableByRPC
@InitiatingFlow
class TransferTHBTokenFlow(private val amount: BigDecimal,
                           private val newHolder: Party): FlowLogic<SignedTransaction>() {
    override val progressTracker = ProgressTracker()

    lateinit var issuer: Party

    @Suspendable
    override fun call(): SignedTransaction {

        issuer = serviceHub.identityService.wellKnownPartyFromX500Name(CordaX500Name.parse("O=PartyA,L=London,C=GB"))!!

        inspect()

        val participants = listOf(ourIdentity, newHolder)

        val changeHolder = serviceHub.keyManagementService.freshKeyAndCert(ourIdentityAndCert, false).party.anonymise()

        val thbToken = amount of THB
        // Transfer token to new holder
        return subFlow(MoveFungibleTokens(
                partiesAndAmounts = listOf(PartyAndAmount(newHolder, thbToken)),
                observers = emptyList(),
                queryCriteria = null,
                changeHolder = changeHolder
        ))

    }

    @Suspendable
    private fun inspect() {
        val myAmount = getMyToken(
                serviceHub = serviceHub,
                holder = ourIdentity,
                issuer = issuer
        )
        "Not have enough amount for transfer." using (myAmount >= amount)
    }

}