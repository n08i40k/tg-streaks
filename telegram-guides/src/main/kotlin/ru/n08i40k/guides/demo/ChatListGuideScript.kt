package ru.n08i40k.guides.demo

import ru.n08i40k.guides.model.GuideAction.*
import ru.n08i40k.guides.model.GuideChat
import ru.n08i40k.guides.model.GuideMessage
import ru.n08i40k.guides.model.GuideProfile
import ru.n08i40k.guides.model.GuideScript
import ru.n08i40k.guides.model.guideScript
import kotlin.time.Duration.Companion.milliseconds

object ChatListGuideScript {
    private const val PROFILE_CHAT = 9L

    fun create(): GuideScript {
        val now = (System.currentTimeMillis() / 1000).toInt()
        val history = listOf(
            GuideMessage(1, "Привет! Это демонстрационный чат 👋", outgoing = false, date = now - 300),
            GuideMessage(2, "А выглядит как настоящий Telegram", outgoing = true, date = now - 240),
            GuideMessage(
                3,
                "Да! Аватар и имя настоящие, а сообщения заданы сценарием.",
                outgoing = false,
                date = now - 180,
            ),
        )
        val anna = GuideChat(1, "Анна", "Привет! Как дела?")
        val chats = listOf(
            anna,
            GuideChat(2, "Избранное", "Идеи на выходные", pinned = true),
            GuideChat(3, "Дизайн", "Новый вариант уже готов", unreadCount = 3, muted = true),
            GuideChat(4, "Михаил", "До встречи!"),
            GuideChat(5, "Мария", "Спасибо за помощь"),
            GuideChat(6, "Алексей", "Отправил фотографии"),
            GuideChat(7, "Путешествия", "Куда поедем дальше?", muted = true),
            GuideChat(8, "София", "Давай завтра в шесть"),
            GuideChat(PROFILE_CHAT, "", "Давай завтра в шесть", profile = GuideProfile.Username("durov")),
        )
        return guideScript("Список чатов") {
            step("Знакомый список чатов", "Это демонстрация. Нажимай «Далее», чтобы менять сцену.") {
                +SetTitle("Telegram")
                +ShowChats(chats)
            }

            step("Выделяем нужный чат", "Сценарий может направить внимание на отдельный диалог.") {
                +ScrollToChat(anna.id)
                +TapChat(anna.id)
                +HighlightChat(anna.id)
            }

            step("Приходит сообщение", "Текст и счётчик обновляются действиями сценария.") {
                +Wait(350.milliseconds)
                +UpdateChat(
                    anna.copy(
                        message = "Продолжим нашу серию?",
                        unreadCount = 1,
                    )
                )
                +Wait(450.milliseconds)
            }

            step("Перемещаемся по списку", "Прокруткой тоже управляет сценарий, а не пользователь.") {
                +HighlightChat(null)
                +ScrollToChat(8)
                +TapChat(8)
                +HighlightChat(8)
            }

            step("Открываем чат", "Родной интерфейс и реальный профиль, история — из сценария.") {
                +HighlightChat(null)
                +ScrollToChat(PROFILE_CHAT)
                +TapChat(PROFILE_CHAT)
                +OpenChat(PROFILE_CHAT, history)
            }

            step("Показываем касание", "Сценарий показывает касание и добавляет локальное сообщение.") {
                +TapAt(.5f, .95f)
                +ShowMessages(
                    history + GuideMessage(
                        4,
                        "Это сообщение тоже фейковое — оно видно только в гайде.",
                        outgoing = true,
                        date = now,
                    )
                )
            }

            step("Возвращаемся к началу", "Чат обновлён и закреплён. Демонстрация завершена.") {
                +ReturnToChats
                +HighlightChat(null)
                +ScrollToChat(anna.id)
                +UpdateChat(anna.copy(message = "Договорились!", pinned = true))
                +HighlightChat(anna.id)
            }
        }
    }
}
