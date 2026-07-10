import type { ReactNode } from "react";
import { Footer, Layout, Navbar } from "nextra-theme-docs";
import { Banner, Head } from "nextra/components";
import { getPageMap } from "nextra/page-map";
import "nextra-theme-docs/style.css";

export const metadata = {
  title: {
    default: "tg-streaks — документация",
    template: "%s — tg-streaks",
  },
  description:
    "Пользовательская документация плагина tg-streaks для extera/AyuGram",
};

const navbar = (
  <Navbar
    logo={<b>tg-streaks</b>}
    projectLink="https://github.com/n08i40k/tg-streaks"
  />
);

const footer = (
  <Footer>
    {new Date().getFullYear()} © tg-streaks. Документация для пользователей
    плагина.
  </Footer>
);

export default async function RootLayout({
  children,
}: {
  children: ReactNode;
}) {
  return (
    <html lang="ru" dir="ltr" suppressHydrationWarning>
      <Head />
      <body>
        <Layout
          banner={
            <Banner storageKey="tg-streaks-docs-banner">
              Это документация для пользователей плагина tg-streaks
            </Banner>
          }
          navbar={navbar}
          pageMap={await getPageMap()}
          docsRepositoryBase="https://github.com/n08i40k/tg-streaks/tree/master/docs"
          footer={footer}
          editLink="Предложить правку на GitHub"
          feedback={{ content: null }}
        >
          {children}
        </Layout>
      </body>
    </html>
  );
}
