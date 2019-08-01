package com.template.flows

import co.paralleluniverse.fibers.Suspendable
import com.r3.corda.lib.tokens.contracts.types.TokenType
import com.r3.corda.lib.tokens.contracts.utilities.heldBy
import com.r3.corda.lib.tokens.contracts.utilities.issuedBy
import com.r3.corda.lib.tokens.contracts.utilities.of
import com.r3.corda.lib.tokens.contracts.utilities.withNotary
import com.r3.corda.lib.tokens.money.FiatCurrency
import com.r3.corda.lib.tokens.workflows.flows.rpc.ConfidentialIssueTokens
import com.r3.corda.lib.tokens.workflows.flows.rpc.CreateEvolvableTokens
import com.r3.corda.lib.tokens.workflows.flows.rpc.IssueTokens
import com.r3.corda.lib.tokens.workflows.utilities.getPreferredNotary
import com.r3.corda.lib.tokens.workflows.utilities.heldBy
import com.template.states.AKKTokenType
import net.corda.core.contracts.Amount
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.StartableByRPC
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.ProgressTracker
import java.math.BigDecimal

@StartableByRPC
class IssueAkkToken(private val reference: String,
                    private val valuation: BigDecimal,
                    private val otherParty: Party): FlowLogic<SignedTransaction>() {
    override val progressTracker = ProgressTracker()

    @Suspendable
    override fun call(): SignedTransaction {

        val ourParty = ourIdentity


        // From within the flow.
        val akkTokenType = AKKTokenType(
                reference = reference,
                valuation = valuation.THB,
                maintainers = listOf(ourParty, otherParty),
                fractionDigits = 2

        )

        //val house = House("24 Leinster Gardens, Bayswater, London", 900_000.GBP, listOf(issuerParty), linearId = UniqueIdentifier())
        val notary: Party = getPreferredNotary(serviceHub) // Or provide notary party using your favourite function from NotaryUtilities.
        // We need to create the evolvable token first.
        subFlow(CreateEvolvableTokens(akkTokenType withNotary notary))

        val akkPtr = akkTokenType.toPointer<AKKTokenType>()

        // Create NonFungibleToken
        val issuedToken = akkPtr issuedBy ourParty heldBy ourParty
        val result = subFlow(ConfidentialIssueTokens(listOf(issuedToken)))

        // Issue money
        val moneyA = 1000000 of THB issuedBy ourParty heldBy ourParty
        val moneyB = 900000 of THB issuedBy ourParty heldBy otherParty
        subFlow(IssueTokens(listOf(moneyA), emptyList()))
        subFlow(IssueTokens(listOf(moneyB), emptyList()))

        return result
    }
}

val THB = FiatCurrency.getInstance("THB")
fun THB(amount: BigDecimal): Amount<TokenType> = com.r3.corda.lib.tokens.contracts.utilities.amount(amount, THB)
val BigDecimal.THB: Amount<TokenType> get() = THB(this)