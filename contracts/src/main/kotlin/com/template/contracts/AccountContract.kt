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

        val updateReceiverAmountCommand = tx.commands.select<Commands.UpdateReceiverAmount>().singleOrNull()
        val updateSenderAmountCommand = tx.commands.select<Commands.UpdateSenderAmount>().singleOrNull()

        val eligibleList = listOfNotNull(updateReceiverAmountCommand, updateSenderAmountCommand).map { it.value }
        if (eligibleList.isNotEmpty()) {

            val sortedList = tx.commands.filter { eligibleList.contains(it.value) }.toSet()

            // We want to verify by sorted commands in the list via FIFO.
            sortedList.forEach {
                when (it.value) {
                    is Commands.UpdateSenderAmount -> verifyUpdateSenderAmount(tx, updateSenderAmountCommand!!.value, updateSenderAmountCommand.signers.toSet())
                    is Commands.UpdateReceiverAmount -> verifyUpdateReceiverAmount(tx, updateReceiverAmountCommand!!.value, updateReceiverAmountCommand.signers.toSet())
                    else -> throw IllegalArgumentException("Unrecognised command.")
                }
            }

        } else {
            val command = tx.commands.requireSingleCommand<Commands>()
            val setOfSigners = command.signers.toSet()
            when (command.value) {
                is Commands.Create -> verifyCreate(tx, setOfSigners)
            }
        }

    }

    private fun verifyCreate(tx: LedgerTransaction, signers: Set<PublicKey>) = requireThat {
        //TODO
    }

    private fun verifyUpdateReceiverAmount(tx: LedgerTransaction, command: Commands.UpdateReceiverAmount, signers: Set<PublicKey>) = requireThat {
        //TODO
    }

    private fun verifyUpdateSenderAmount(tx: LedgerTransaction, command: Commands.UpdateSenderAmount, signers: Set<PublicKey>) = requireThat {
        //TODO
    }

}