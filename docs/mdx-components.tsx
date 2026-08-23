import type { MDXComponents } from "nextra/mdx-components";
import { useMDXComponents as getDocsMDXComponents } from "nextra-theme-docs";
import { Screenshot } from "./components/Screenshot";
import { SettingLink } from "./components/SettingLink";

const docsComponents = getDocsMDXComponents({
  Screenshot,
  SettingLink,
});

export function useMDXComponents(components?: MDXComponents) {
  return {
    ...docsComponents,
    ...components,
  };
}
