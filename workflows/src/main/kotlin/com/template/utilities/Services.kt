package com.template.utilities

import com.r3.corda.lib.tokens.contracts.internal.schemas.PersistentFungibleToken
import com.r3.corda.lib.tokens.contracts.states.FungibleToken
import com.r3.corda.lib.tokens.contracts.types.TokenType
import com.r3.corda.lib.tokens.money.FiatCurrency
import com.r3.corda.lib.tokens.workflows.utilities.tokenAmountCriteria
import net.corda.core.contracts.Amount
import net.corda.core.identity.Party
import net.corda.core.internal.sumByLong
import net.corda.core.node.ServiceHub
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.Builder.equal
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.node.services.vault.builder
import java.lang.IllegalArgumentException
import java.math.BigDecimal

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