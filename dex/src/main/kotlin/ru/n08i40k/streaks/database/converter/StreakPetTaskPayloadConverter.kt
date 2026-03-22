package ru.n08i40k.streaks.database.converter

import androidx.room.TypeConverter
import org.json.JSONObject
import ru.n08i40k.streaks.data.StreakPetTaskPayload

class StreakPetTaskPayloadConverter {
    @TypeConverter
    fun fromPayload(payload: StreakPetTaskPayload?): String? =
        when (payload) {
            null -> null

            is StreakPetTaskPayload.ExchangeOneMessage ->
                JSONObject()
                    .put("kind", "exchange_one_message")
                    .put("fromOwnerMessageId", payload.fromOwnerMessageId)
                    .put("fromPeerMessageId", payload.fromPeerMessageId)
                    .toString()

            is StreakPetTaskPayload.SendFourMessagesEach ->
                JSONObject()
                    .put("kind", "send_four_messages_each")
                    .put("fromOwnerMessagesCount", payload.fromOwnerMessagesCount)
                    .put("fromOwnerLastMessageId", payload.fromOwnerLastMessageId)
                    .put("fromPeerMessagesCount", payload.fromPeerMessagesCount)
                    .put("fromPeerLastMessageId", payload.fromPeerLastMessageId)
                    .toString()

            is StreakPetTaskPayload.SendTenMessagesEach ->
                JSONObject()
                    .put("kind", "send_ten_messages_each")
                    .put("fromOwnerMessagesCount", payload.fromOwnerMessagesCount)
                    .put("fromOwnerLastMessageId", payload.fromOwnerLastMessageId)
                    .put("fromPeerMessagesCount", payload.fromPeerMessagesCount)
                    .put("fromPeerLastMessageId", payload.fromPeerLastMessageId)
                    .toString()
        }

    @TypeConverter
    fun toPayload(value: String?): StreakPetTaskPayload? {
        if (value == null) {
            return null
        }

        val json = JSONObject(value)

        return when (json.getString("kind")) {
            "exchange_one_message" ->
                StreakPetTaskPayload.ExchangeOneMessage(
                    fromOwnerMessageId = json.optNullableInt("fromOwnerMessageId"),
                    fromPeerMessageId = json.optNullableInt("fromPeerMessageId"),
                )

            "send_four_messages_each" ->
                StreakPetTaskPayload.SendFourMessagesEach(
                    fromOwnerMessagesCount = json.optInt("fromOwnerMessagesCount"),
                    fromOwnerLastMessageId = json.optNullableInt("fromOwnerLastMessageId"),
                    fromPeerMessagesCount = json.optInt("fromPeerMessagesCount"),
                    fromPeerLastMessageId = json.optNullableInt("fromPeerLastMessageId"),
                )

            "send_ten_messages_each" ->
                StreakPetTaskPayload.SendTenMessagesEach(
                    fromOwnerMessagesCount = json.optInt("fromOwnerMessagesCount"),
                    fromOwnerLastMessageId = json.optNullableInt("fromOwnerLastMessageId"),
                    fromPeerMessagesCount = json.optInt("fromPeerMessagesCount"),
                    fromPeerLastMessageId = json.optNullableInt("fromPeerLastMessageId"),
                )

            else -> throw IllegalArgumentException("Unknown streak pet task payload kind: ${json.getString("kind")}")
        }
    }

    private fun JSONObject.optNullableInt(name: String): Int? =
        if (isNull(name)) null else optInt(name)
}
