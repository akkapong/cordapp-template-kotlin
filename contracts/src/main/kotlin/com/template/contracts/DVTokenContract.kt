package com.template.contracts

import com.r3.corda.lib.tokens.contracts.EvolvableTokenContract
import com.template.states.AKKTokenType
import com.template.states.DVToken
import net.corda.core.contracts.Amount
import net.corda.core.contracts.Contract
import net.corda.core.transactions.LedgerTransaction
import java.time.Instant

class DVTokenContract : EvolvableTokenContract(), Contract {

    override fun additionalCreateChecks(tx: LedgerTransaction) {
        // Not much to do for this example token.
        val newDV = tx.outputStates.single() as DVToken
        newDV.apply {
            require(date > Instant.now().minusMillis(5000)) { "Date must less than current." }
        }
    }

    override fun additionalUpdateChecks(tx: LedgerTransaction) {
//        val oldHouse = tx.inputStates.single() as AKKTokenType
//        val newHouse = tx.outputStates.single() as AKKTokenType
//        require(oldHouse.reference == newHouse.reference) { "The reference cannot change." }
//        require(newHouse.valuation > Amount.zero(newHouse.valuation.token)) { "Valuation must be greater than zero." }
    }
}