package com.template.flows

import co.paralleluniverse.fibers.Suspendable
import com.r3.corda.lib.tokens.contracts.internal.schemas.PersistentFungibleToken
import com.r3.corda.lib.tokens.contracts.states.FungibleToken
import com.r3.corda.lib.tokens.contracts.types.TokenPointer
import com.r3.corda.lib.tokens.money.FiatCurrency
import com.r3.corda.lib.tokens.workflows.utilities.tokenAmountCriteria
import com.template.states.DVToken
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.StartableByRPC
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.node.services.vault.builder

@StartableByRPC
class QueryToken(val tokenType: String): FlowLogic<List<FungibleToken>>() {

    @Suspendable
    override fun call(): List<FungibleToken> {

        return when (tokenType) {
            "DV" -> QueryDVToken()
            else -> queryTHBToken()
        }

    }

    private fun queryTHBToken(): List<FungibleToken> {
        val holderCriteria = QueryCriteria.VaultCustomQueryCriteria(builder {
            PersistentFungibleToken::holder.equal(ourIdentity)
        })
        val criteria = tokenAmountCriteria(FiatCurrency.getInstance("THB")).and(holderCriteria)
        return serviceHub.vaultService.queryBy<FungibleToken>(criteria).states.map { it.state.data }
    }

    private fun QueryDVToken(): List<FungibleToken> {
        val holderCriteria = QueryCriteria.VaultCustomQueryCriteria(builder {
            PersistentFungibleToken::holder.equal(ourIdentity)
        })

        val dvPtr = getDvTokenPointer()

        val criteria = tokenAmountCriteria(dvPtr).and(holderCriteria)
        return serviceHub.vaultService.queryBy<FungibleToken>(criteria).states.map { it.state.data }
    }

    private fun getDvTokenPointer(): TokenPointer<DVToken> {
        val queryCriteria = QueryCriteria.LinearStateQueryCriteria()
        val dvToken = serviceHub.vaultService.queryBy<DVToken>(queryCriteria).states.single().state.data

        return dvToken.toPointer()
    }


}