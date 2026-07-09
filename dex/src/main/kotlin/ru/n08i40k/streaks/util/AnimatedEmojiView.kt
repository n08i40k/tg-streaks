package ru.n08i40k.streaks.util

import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
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
    fun apply(
        imageView: BackupImageView,
        documentId: Long,
        size: Int,
        color: Int? = null,
    ) {
        fun applyDocument(document: TLRPC.Document) {
            val thumb = FileLoader.getClosestPhotoSizeWithSize(document.thumbs, size)

            val thumbDrawable = DocumentObject.getSvgThumb(
                document.thumbs,
                Theme.key_windowBackgroundWhiteGrayIcon,
                0.2f,
                true
            )?.apply {
                if (MessageObject.isAnimatedStickerDocument(document, true))
                    overrideWidthAndHeight(512, 512)
            }

            val mediaLocation = ImageLocation.getForDocument(document)

            val mediaFilter = if ("video/webm" == document.mime_type)
                "${size}_${size}_${ImageLoader.AUTOPLAY_FILTER}"
            else
                "${size}_${size}"

            imageView.setImage(
                mediaLocation,
                mediaFilter,
                ImageLocation.getForDocument(thumb, document),
                "${size}_${size}",
                thumbDrawable,
                document
            )

            val animatedEmoji = AnimatedEmojiDrawable.make(UserConfig.selectedAccount, 0, document)

            imageView.setColorFilter(
                if (animatedEmoji.canOverrideColor())
                    PorterDuffColorFilter(
                        color ?: Theme.getColor(Theme.key_windowBackgroundWhiteBlueIcon),
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
    }
}