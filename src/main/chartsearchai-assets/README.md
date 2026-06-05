# chartsearchai assets

The contents of this directory are bundled into `appdata/chartsearchai/` of the
standalone zip (see the `chartsearchai.assets.dir` property in `pom.xml` and the
fileSet in `src/main/assembly/zip-standalone.xml`).

The chartsearchai module expects (paths relative to `appdata/chartsearchai/`):

- `model.onnx` + `vocab.txt` — ONNX embedding model (all-MiniLM-L6-v2,
  <https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2>), used only
  when `chartsearchai.embedding.preFilter` is enabled.
- `gemma-4-E4B-it-Q4_K_M.gguf` — the default local LLM
  (`chartsearchai.llm.modelFilePath`).
- `bin/llama-server` — llama.cpp server binary used by the local engine.

These files are large (multi-GB) and are not checked into this repository. To
bundle real assets, point the build at a populated directory:

```bash
mvn package -Dchartsearchai.assets.dir=/path/to/chartsearchai-assets \
    -Dopenmrs.version=2.9.0-SNAPSHOT -Drefapp.version=3.7.0-SNAPSHOT
```

Without the override, only this README is bundled and the models must be placed
in `appdata/chartsearchai/` after installation.
