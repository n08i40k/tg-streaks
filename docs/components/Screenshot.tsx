"use client";

import { useState } from "react";

type ScreenshotProps = {
  /** Путь внутри public/screenshots для светлой темы (и для обеих, если `dark` не задан). */
  file: string;
  /** Необязательный путь внутри public/screenshots для тёмной темы. */
  dark?: string;
  /** Что должно быть изображено на скриншоте. */
  caption: string;
};

const basePath = process.env.NEXT_PUBLIC_BASE_PATH || "";

const src = (file: string) => `${basePath}/screenshots/${file}`;

/**
 * Скриншот с подписью. Если для тёмной темы передан проп `dark`,
 * подставляется отдельный файл — переключение делает CSS по классу
 * `html.dark`, который вешает next-themes.
 *
 * Пока реального файла нет в `public/screenshots/<file>`, показывается
 * пунктирный плейсхолдер с ожидаемым путём.
 */
export function Screenshot({ file, dark, caption }: ScreenshotProps) {
  const [broken, setBroken] = useState(false);

  if (broken) {
    return (
      <div
        style={{
          display: "flex",
          flexDirection: "column",
          alignItems: "center",
          justifyContent: "center",
          gap: "0.5rem",
          border: "2px dashed var(--x-color-neutral-400, #a3a3a3)",
          borderRadius: "0.75rem",
          padding: "2.5rem 1.5rem",
          margin: "1.5rem 0",
          textAlign: "center",
          color: "var(--x-color-neutral-500, #737373)",
          background: "var(--x-color-neutral-100, rgba(163, 163, 163, 0.08))",
        }}
      >
        <span style={{ fontWeight: 600 }}>{caption}</span>
        <code style={{ fontSize: "0.85rem", opacity: 0.8 }}>
          public/screenshots/{file}
          {dark ? ` · ${dark}` : ""}
        </code>
      </div>
    );
  }

  return (
    <figure className="nextra-screenshot">
      <img
        className="screenshot-light"
        src={src(file)}
        alt={caption}
        loading="lazy"
        onError={() => setBroken(true)}
      />
      {dark ? (
        <img
          className="screenshot-dark"
          src={src(dark)}
          alt={caption}
          loading="lazy"
        />
      ) : null}
      <figcaption>{caption}</figcaption>

      <style jsx global>{`
        .nextra-screenshot {
          display: flex;
          flex-direction: column;
          align-items: center;
          gap: 0.5rem;
          margin: 1.5rem 0;
        }
        .nextra-screenshot img {
          max-width: 100%;
          height: auto;
          border-radius: 0.75rem;
          border: 1px solid var(--x-color-neutral-200, #e5e5e5);
        }
        .nextra-screenshot figcaption {
          font-size: 0.9rem;
          color: var(--x-color-neutral-500, #737373);
          text-align: center;
        }
        .nextra-screenshot .screenshot-dark {
          display: none;
        }
        html.dark .nextra-screenshot .screenshot-light {
          display: none;
        }
        html.dark .nextra-screenshot .screenshot-dark {
          display: block;
        }
      `}</style>
    </figure>
  );
}
