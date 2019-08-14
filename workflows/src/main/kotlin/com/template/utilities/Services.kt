package com.template.utilities

import com.r3.corda.lib.tokens.contracts.internal.schemas.PersistentFungibleToken
import com.r3.corda.lib.tokens.contracts.states.FungibleToken
import com.r3.corda.lib.tokens.contracts.types.TokenType
import com.r3.corda.lib.tokens.money.FiatCurrency
import com.r3.corda.lib.tokens.workflows.utilities.tokenAmountCriteria
import net.corda.core.contracts.Amount
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.internal.sumByLong
import net.corda.core.node.ServiceHub
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.node.services.vault.builder
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import java.lang.IllegalArgumentException
import java.math.BigDecimal
import java.security.PublicKey

val THB = FiatCurrency.getInstance("THB")
fun THB(amount: BigDecimal): Amount<TokenType> = com.r3.corda.lib.tokens.contracts.utilities.amount(amount, THB)
val BigDecimal.THB: Amount<TokenType> get() = THB(this)

object Conditions {
    /** Throws [IllegalArgumentException] if the given expression evaluates to false. */
    @Suppress("NOTHING_TO_INLINE")   // Inlining this takes it out of our committed ABI.
    inline infix fun String.using(expr: Boolean) {
        if (!expr) throw IllegalArgumentException("Failed requirement: $this")
    }
}

fun getMyToken(serviceHub: ServiceHub, holder: Party, issuer: Party, currencyCode: String = "THB") : BigDecimal {
    val holderCriteria = QueryCriteria.VaultCustomQueryCriteria(builder {
        PersistentFungibleToken::holder.equal(holder)
    })
    val issuerCriteria = QueryCriteria.VaultCustomQueryCriteria(builder {
        PersistentFungibleToken::issuer.equal(issuer)
    })
    val criteria = tokenAmountCriteria(FiatCurrency.getInstance(currencyCode)).and(holderCriteria).and(issuerCriteria)
    val allFungibleToken =  serviceHub.vaultService.queryBy<FungibleToken>(criteria).states.map { it.state.data }

    return allFungibleToken.sumByLong { it.amount.quantity }.toBigDecimal()
}

fun getAllParties(serviceHub: ServiceHub) : List<Party> {
    return allParties.map {
        serviceHub.identityService.wellKnownPartyFromX500Name(CordaX500Name.parse(it))!!
    }
}


val allParties = listOf(
        "O=PartyA,L=London,C=GB",
        "O=PartyB,L=New York,C=US"
)

fun TransactionBuilder.everyOneExceptMe(serviceHub: ServiceHub, ourIdentity: Party): Set<Party> {
    // Get all participants from the utx
    val ltx = this.copy()
            .toLedgerTransaction(serviceHub)
    val allParticipants = mutableListOf<Party>()

    allParticipants.addAll(ltx.inputStates.map { it.participants.mapNotNull { resolveIdentity(serviceHub, it) } }.flatten())
    allParticipants.addAll(ltx.outputStates.map { it.participants.mapNotNull { resolveIdentity(serviceHub, it) } }.flatten())

    // Add all the participants to the FlowSession except me
    return allParticipants.toSet().minus(ourIdentity)
}

fun resolveIdentity(serviceHub: ServiceHub, abstractParty: AbstractParty): Party? {
    return serviceHub.identityService.wellKnownPartyFromAnonymous(abstractParty)
}

fun resolveKey(serviceHub: ServiceHub, key: PublicKey): Party? {
    // Party is resolved but is still paired with the old anonymous key
    val partyWithAnonymousKey = serviceHub.identityService.partyFromKey(key)
    return partyWithAnonymousKey?.let {
        // If we can resolve the party from key before, then we want to return the party paired with the public key
        // instead of still referring to the old anonymous key
        serviceHub.identityService.wellKnownPartyFromAnonymous(partyWithAnonymousKey)
    }
}

fun SignedTransaction.getParticipantsKey(ourIdentity: Party): Set<PublicKey> {
    val partSignedTx = this
    val requiredSign = partSignedTx.requiredSigningKeys
    val notaryKey = partSignedTx.tx.notary?.owningKey
    return (if (notaryKey != null) requiredSign - notaryKey else requiredSign).minus(ourIdentity.owningKey)
}