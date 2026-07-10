import type { MDXComponents } from "nextra/mdx-components";
import { useMDXComponents as getDocsMDXComponents } from "nextra-theme-docs";
import { Screenshot } from "./components/Screenshot";

const docsComponents = getDocsMDXComponents({
  Screenshot,
});

export function useMDXComponents(components?: MDXComponents) {
  return {
    ...docsComponents,
    ...components,
  };
}
