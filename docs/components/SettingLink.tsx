type SettingLinkProps = {
  /** Значение `link_alias` настройки в плагине. */
  alias: string;
  /** Текст ссылки. */
  children?: React.ReactNode;
};

const PLUGIN_ID = "tg-streaks";

/**
 * Ссылка, открывающая конкретную настройку плагина прямо в клиенте.
 * Клиент ловит `t.me/exteraSettings`, открывает настройки плагина и
 * прокручивает список к пункту с этим `link_alias`.
 */
export function SettingLink({ alias, children }: SettingLinkProps) {
  return (
    <p>
      <a
        href={`https://t.me/exteraSettings?p=${PLUGIN_ID}&s=${alias}`}
        style={{
          display: "inline-block",
          padding: "0.35rem 0.75rem",
          borderRadius: "0.6rem",
          fontSize: "0.9rem",
          fontWeight: 500,
          textDecoration: "none",
          border: "1px solid var(--x-color-neutral-200, #e5e5e5)",
          background: "var(--x-color-neutral-100, rgba(163, 163, 163, 0.08))",
        }}
      >
        ↗ {children ?? "Открыть настройку в клиенте"}
      </a>
    </p>
  );
}
