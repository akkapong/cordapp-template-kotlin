package com.template.contracts

import net.corda.core.contracts.*
import net.corda.core.transactions.LedgerTransaction
import net.corda.core.utilities.loggerFor
import java.security.PublicKey

class AccountContract : Contract {

    companion object {
        @JvmStatic
        val ACCOUNT_CONTRACT_ID = "com.template.contracts.AccountContract"
        val logger = loggerFor<AccountContract>()
    }

    interface Commands : CommandData {
        class Create : TypeOnlyCommandData(), Commands
        class UpdateReceiverAmount : TypeOnlyCommandData(), Commands
        class UpdateSenderAmount : TypeOnlyCommandData(), Commands
    }

    override fun verify(tx: LedgerTransaction) {
        val command = tx.commands.requireSingleCommand<Commands>()
        val setOfSigners = command.signers.toSet()
        when (command.value) {
            is Commands.Create -> verifyCreate(tx, setOfSigners)
            is Commands.UpdateReceiverAmount -> verifyUpdateReceiverAmount(tx, setOfSigners)
            is Commands.UpdateSenderAmount -> verifyUpdateSenderAmount(tx, setOfSigners)
        }
    }

    private fun verifyCreate(tx: LedgerTransaction, signers: Set<PublicKey>) = requireThat {
        //TODO
    }

    private fun verifyUpdateReceiverAmount(tx: LedgerTransaction, signers: Set<PublicKey>) = requireThat {
        //TODO
    }

    private fun verifyUpdateSenderAmount(tx: LedgerTransaction, signers: Set<PublicKey>) = requireThat {
        //TODO
    }

}