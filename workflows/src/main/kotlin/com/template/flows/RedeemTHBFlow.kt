package com.template.flows

import co.paralleluniverse.fibers.Suspendable
import com.r3.corda.lib.tokens.contracts.utilities.of
import com.r3.corda.lib.tokens.workflows.flows.move.MoveTokensFlowHandler
import com.r3.corda.lib.tokens.workflows.flows.redeem.RedeemFungibleTokensFlow
import com.r3.corda.lib.tokens.workflows.flows.redeem.RedeemTokensFlowHandler
import com.r3.corda.lib.tokens.workflows.flows.rpc.RedeemFungibleTokens
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
class RedeemTHBFlow(private val amount: BigDecimal): FlowLogic<SignedTransaction>() {
    override val progressTracker = ProgressTracker()

    lateinit var issuer: Party

    @Suspendable
    override fun call(): SignedTransaction {

        issuer = serviceHub.identityService.wellKnownPartyFromX500Name(CordaX500Name.parse("O=PartyA,L=London,C=GB"))!!

        inspect()

        val thbAmount = amount of THB

        return subFlow(RedeemFungibleTokens(
                amount = thbAmount,
                issuer = issuer,
                observers = emptyList(),
                queryCriteria = null))
    }

    @Suspendable
    private fun inspect() {
        val myAmount = getMyToken(
                serviceHub = serviceHub,
                holder = ourIdentity,
                issuer = issuer
        )
        "Not have enough amount for redeem." using (myAmount >= amount)
    }
}
