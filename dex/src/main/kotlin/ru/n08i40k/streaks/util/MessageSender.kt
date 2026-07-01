package ru.n08i40k.streaks.util

import org.telegram.messenger.AccountInstance
import org.telegram.messenger.SendMessagesHelper

object MessageSender {
    @Suppress("EnumEntryName")
    private enum class SendMessagesHelperRev {
        Pre_12_2_0,
        Pre_12_7_0,
        Latest;
    }

    private val currentRevision by lazy {
        if (isClientVersionBelow("12.2.0"))
            return@lazy SendMessagesHelperRev.Pre_12_2_0

        if (isClientVersionBelow("12.7.0"))
            return@lazy SendMessagesHelperRev.Pre_12_7_0

        return@lazy SendMessagesHelperRev.Latest
    }

    private val prepareSendingText by lazy {
        when (currentRevision) {
            SendMessagesHelperRev.Pre_12_2_0 ->
                SendMessagesHelper::class.java.getDeclaredMethod(
                    "prepareSendingText",
                    AccountInstance::class.java,
                    String::class.java,
                    Long::class.java,
                    Boolean::class.java,
                    Int::class.java,
                    Long::class.java
                )

            SendMessagesHelperRev.Pre_12_7_0 ->
                SendMessagesHelper::class.java.getDeclaredMethod(
                    "prepareSendingText",
                    AccountInstance::class.java,
                    String::class.java,
                    Long::class.java,
                    Boolean::class.java,
                    Int::class.java,
                    Int::class.java,
                    Long::class.java
                )

            SendMessagesHelperRev.Latest ->
                SendMessagesHelper::class.java.getDeclaredMethod(
                    "prepareSendingText",
                    AccountInstance::class.java,
                    CharSequence::class.java,
                    Long::class.java,
                    Boolean::class.java,
                    Int::class.java,
                    Int::class.java,
                    Long::class.java
                )
        }
    }

    fun send(accountId: Int, peerId: Long, message: String) {
        val account = AccountInstance.getInstance(accountId)

        when (currentRevision) {
            SendMessagesHelperRev.Pre_12_2_0 ->
                prepareSendingText.invoke(null, account, message, peerId, false, 0, 0L)

            SendMessagesHelperRev.Pre_12_7_0 ->
                prepareSendingText.invoke(null, account, message, peerId, false, 0, 0, 0L)

            SendMessagesHelperRev.Latest ->
                prepareSendingText.invoke(
                    null,
                    account,
                    message as CharSequence,
                    peerId,
                    false,
                    0,
                    0,
                    0L
                )
        }
    }
}