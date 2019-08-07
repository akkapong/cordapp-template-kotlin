package com.template.schemas

import net.corda.core.schemas.MappedSchema
import net.corda.core.schemas.PersistentState
import java.math.BigDecimal
import java.util.*
import javax.persistence.Column
import javax.persistence.Entity
import javax.persistence.Table

/**
 * The family of schemas for IOUState.
 */
object AccountSchemas

/**
 * An IOUState schema.
 */
object AccountSchema : MappedSchema(
        schemaFamily = AccountSchemas.javaClass,
        version = 1,
        mappedTypes = listOf(PersistentAccount::class.java)) {
    @Entity
    @Table(name = "account_state")
    class PersistentAccount(
            @Column(name = "account_name")
            var accountName: String,

            @Column(name = "party_a")
            var partyA: String,

            @Column(name = "party_b")
            var partyB: String,

            @Column(name = "amount")
            var amount: BigDecimal?,

            @Column(name = "token_identifier")
            var tokenIdentifier: String?,

            @Column(name = "linear_id")
            var linearId: UUID
    ) : PersistentState() {
        // Default constructor required by hibernate.
        constructor(): this("", "", "", null, null, UUID.randomUUID())
    }
}