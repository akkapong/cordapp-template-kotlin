package com.template.states

import com.r3.corda.lib.tokens.contracts.states.FungibleToken
import com.template.contracts.AccountContract
import com.template.schemas.AccountSchema
import net.corda.core.contracts.BelongsToContract
import net.corda.core.contracts.LinearState
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.PersistentState
import net.corda.core.schemas.QueryableState

@BelongsToContract(AccountContract::class)
data class Account (
        val accountName: String,
        val ourParty: Party,
        val otherParty: Party,
        val amount: FungibleToken? = null,
        override val linearId: UniqueIdentifier = UniqueIdentifier()
) : LinearState, QueryableState {
    override val participants: List<AbstractParty> get() = listOfNotNull(ourParty, otherParty)

    override fun generateMappedObject(schema: MappedSchema): PersistentState {
        return when (schema) {
            is AccountSchema -> AccountSchema.PersistentAccount(
                    this.accountName,
                    this.ourParty.name.toString(),
                    this.otherParty.name.toString(),
                    this.amount?.amount?.toDecimal(),
                    this.amount?.issuedTokenType?.tokenType?.tokenIdentifier,
                    this.linearId.id
            )
            else -> throw IllegalArgumentException("Unrecognised schema $schema")
        }
    }

    override fun supportedSchemas(): Iterable<MappedSchema> = listOf(AccountSchema)
}