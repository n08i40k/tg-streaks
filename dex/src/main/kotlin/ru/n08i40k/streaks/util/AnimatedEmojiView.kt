package ru.n08i40k.streaks.util

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.view.View
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.DocumentObject
import org.telegram.messenger.FileLoader
import org.telegram.messenger.ImageLoader
import org.telegram.messenger.ImageLocation
import org.telegram.messenger.MessageObject
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.Theme
import org.telegram.ui.Components.AnimatedEmojiDrawable
import org.telegram.ui.Components.BackupImageView

object AnimatedEmojiView {
    fun create(
        context: Context,
        documentId: Long,
        size: Int = 90,
        roundRadius: Int = AndroidUtilities.dp(4f),
        resourcesProvider: Theme.ResourcesProvider? = null,
    ): View {
        val icon = BackupImageView(context)
        icon.setLayerNum(7)
        icon.setRoundRadius(roundRadius)

        fun applyDocument(document: TLRPC.Document) {
            val thumb = FileLoader.getClosestPhotoSizeWithSize(document.thumbs, size)
            val thumbDrawable =
                DocumentObject.getSvgThumb(
                    document.thumbs,
                    Theme.key_windowBackgroundWhiteGrayIcon,
                    0.2f,
                    true
                )?.apply {
                    if (MessageObject.isAnimatedStickerDocument(document, true)) {
                        overrideWidthAndHeight(512, 512)
                    }
                }

            val mediaLocation = ImageLocation.getForDocument(document)
            val mediaFilter =
                if ("video/webm" == document.mime_type)
                    "140_140_${ImageLoader.AUTOPLAY_FILTER}"
                else
                    "140_140"

            icon.setImage(
                mediaLocation,
                mediaFilter,
                ImageLocation.getForDocument(thumb, document),
                "140_140",
                thumbDrawable,
                document
            )

            val animatedEmoji = AnimatedEmojiDrawable.make(UserConfig.selectedAccount, 0, document)

            icon.setColorFilter(
                if (animatedEmoji.canOverrideColor())
                    PorterDuffColorFilter(
                        Theme.getColor(Theme.key_windowBackgroundWhiteBlueIcon, resourcesProvider),
                        PorterDuff.Mode.SRC_IN
                    )
                else
                    null
            )
        }

        AnimatedEmojiDrawable
            .getDocumentFetcher(UserConfig.selectedAccount)
            .fetchDocument(documentId) { document ->
                AndroidUtilities.runOnUIThread {
                    applyDocument(document)
                }
            }

        return icon
    }
}