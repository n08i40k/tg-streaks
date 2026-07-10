import nextra from "nextra";

const basePath = process.env.NEXT_BASE_PATH || "";

const withNextra = nextra({
  defaultShowCopyCode: true,
});

export default withNextra({
  output: "export",
  basePath,
  assetPrefix: basePath ? `${basePath}/` : undefined,
  images: {
    unoptimized: true,
  },
  env: {
    NEXT_PUBLIC_BASE_PATH: basePath,
  },
});
