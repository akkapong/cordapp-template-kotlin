package com.template.flows

import co.paralleluniverse.fibers.Suspendable
import com.r3.corda.lib.tokens.contracts.utilities.heldBy
import com.r3.corda.lib.tokens.contracts.utilities.issuedBy
import com.r3.corda.lib.tokens.contracts.utilities.of
import com.r3.corda.lib.tokens.contracts.utilities.withNotary
import com.r3.corda.lib.tokens.workflows.flows.rpc.ConfidentialIssueTokens
import com.r3.corda.lib.tokens.workflows.flows.rpc.CreateEvolvableTokens
import com.r3.corda.lib.tokens.workflows.utilities.getPreferredNotary
import com.template.states.DVToken
import com.template.utilities.getAllParties
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.StartableByRPC
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.ProgressTracker
import java.math.BigDecimal
import java.time.Instant

@StartableByRPC
class IssueDVToken(private val amount: BigDecimal): FlowLogic<SignedTransaction>() {
    override val progressTracker = ProgressTracker()

    @Suspendable
    override fun call(): SignedTransaction {

        val dvTokenType = DVToken(
                date = Instant.now(),
                maintainers = getAllParties(serviceHub),
                fractionDigits = 2

        )

        val notary: Party = getPreferredNotary(serviceHub) // Or provide notary party using your favourite function from NotaryUtilities.
        // We need to create the evolvable token first.
        subFlow(CreateEvolvableTokens(dvTokenType withNotary notary))

        val dvPtr = dvTokenType.toPointer<DVToken>()

        val issuer = serviceHub.identityService.wellKnownPartyFromX500Name(CordaX500Name.parse("O=PartyA,L=London,C=GB"))!!

        // Create FungibleToken
        val issuedToken = amount of dvPtr issuedBy issuer heldBy ourIdentity
        return subFlow(ConfidentialIssueTokens(listOf(issuedToken)))
    }
}
