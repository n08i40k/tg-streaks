package ru.n08i40k.guides.profile

import org.telegram.tgnet.TLObject

internal data class GuidePeer(val entity: TLObject, val dialogId: Long)
