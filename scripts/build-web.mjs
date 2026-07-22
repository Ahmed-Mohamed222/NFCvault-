import { readFile, writeFile } from "node:fs/promises";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import vm from "node:vm";

const projectRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const sourcePath = join(projectRoot, "web-src", "app.jsx");
const compilerPath = join(projectRoot, "tools", "babel.min.js");
const outputPath = join(projectRoot, "app", "src", "main", "assets", "www", "app.js");

const [source, compiler] = await Promise.all([
  readFile(sourcePath, "utf8"),
  readFile(compilerPath, "utf8")
]);

const compilerContext = {};
vm.createContext(compilerContext);
vm.runInContext(compiler, compilerContext, { filename: compilerPath });

const result = compilerContext.Babel.transform(source, {
  presets: [
    ["env", { targets: { chrome: "44" }, modules: false }],
    ["react", { runtime: "classic" }]
  ],
  sourceType: "script",
  comments: false,
  compact: true,
  minified: true
});

await writeFile(outputPath, `"use strict";\n${result.code}\n`, "utf8");
console.log(`Built ${outputPath}`);
