package ru.n08i40k.streaks.util

import org.telegram.tgnet.TLObject
import java.lang.reflect.Method

object BadgesCompat {
    data class ReflectionData(
        // BadgesController
        val badgesController: Any,

        // BadgesController::getDocumentId()
        val getBadge: Method,

        // BadgeDTO::getDocumentId()
        val getDocumentId: Method
    )

    private var reflectionData: ReflectionData? = null

    fun init() {
        // Starting from 12.8.0 BadgesController is unobfuscated again
        if (!isClientVersionBelow("12.6.4") && isClientVersionBelow("12.8.0"))
            return

        val controllerClass = Class.forName("com.exteragram.messenger.badges.BadgesController")
        val badgeClass = Class.forName("com.exteragram.messenger.api.dto.BadgeDTO")

        reflectionData = ReflectionData(
            badgesController = controllerClass
                .getDeclaredField("INSTANCE")
                .get(null)
                ?: throw NullPointerException("Failed to get badges controller instance"),

            getBadge = controllerClass
                .getDeclaredMethod("getBadge", TLObject::class.java),

            getDocumentId = badgeClass
                .getDeclaredMethod("getDocumentId")
        )

    }

    fun getDocumentId(obj: TLObject): Long? {
        return with(reflectionData ?: return null) {
            val badge = getBadge.invoke(badgesController, obj)
                ?: return null

            val documentId = getDocumentId.invoke(badge)
                ?: run {
                    reflectionData = null
                    return@with null
                }

            return documentId as Long
        }
    }
}