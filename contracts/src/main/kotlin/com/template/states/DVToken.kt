package com.template.states

import com.r3.corda.lib.tokens.contracts.states.EvolvableTokenType
import com.r3.corda.lib.tokens.contracts.types.TokenType
import com.template.contracts.AKKTokenTypeContract
import com.template.contracts.DVTokenContract
import net.corda.core.contracts.Amount
import net.corda.core.contracts.BelongsToContract
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.identity.Party
import java.time.Instant

@BelongsToContract(DVTokenContract::class)
data class DVToken (
        val date: Instant,
        override val maintainers: List<Party>,
        override val fractionDigits: Int = 0,
        override val linearId: UniqueIdentifier = UniqueIdentifier()
) : EvolvableTokenType()