package ru.n08i40k.streaks.util

import org.telegram.tgnet.TLObject
import java.lang.reflect.Method

object BadgesCompat {
    private var controllerGetBadgeMethod: Method? = null
    private var controllerInstance: Any? = null

    private var badgeGetDocumentIdMethod: Method? = null

    private var initException: Throwable? = null

    init {
        try {
            init()
        } catch (e: Throwable) {
            initException = e
        }
    }

    fun init() {
        if (!isClientVersionBelow("12.6.4"))
            return

        val controllerClass = Class.forName("com.exteragram.messenger.badges.BadgesController")
        val badgeClass = Class.forName("com.exteragram.messenger.api.dto.BadgeDTO")

        controllerGetBadgeMethod = controllerClass
            .getDeclaredMethod("getBadge", TLObject::class.java)

        controllerInstance = controllerClass
            .getDeclaredField("INSTANCE")
            .get(null)
            ?: throw NullPointerException("Failed to get badges controller instance")

        badgeGetDocumentIdMethod = badgeClass
            .getDeclaredMethod("getDocumentId")
    }

    fun getDocumentId(obj: TLObject): Long? {
        if (controllerInstance == null)
            return null

        val badge = controllerGetBadgeMethod!!.invoke(controllerInstance, obj)
            ?: return null

        val documentId = badgeGetDocumentIdMethod!!.invoke(badge)
            ?: return null

        return documentId as Long
    }

    fun takeException(): Throwable? {
        val ex = initException ?: return null
        initException = null
        return ex
    }
}