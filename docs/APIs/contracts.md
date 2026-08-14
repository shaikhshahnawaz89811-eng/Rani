# Internal API Contracts

Every major subsystem exposes a typed contract. UI code should call the contract, not low-level implementations.

## WindowManager
`open`, `close`, `minimize`, `maximize`, `restore`, `focus`, `move`, `resize`, `initializeBounds`.

## FileService
`projectTree`, `read`, `write`, `createFile`, `createFolder`, `rename`, `delete`, `copy`, `move`, `exists`, `listDirectory`, `search`.
All filesystem operations return `FileResult` where an operation can fail. Paths are restricted to the controlled workspace.

## AIService
`chat`, `explainCode`, `generateCode`, `analyzeError`, `suggestFix`, `modifyFile`, `understandProject`, `runDeveloperTask`.

## AI tools
Tools are registered through `ToolRegistry` and executed through `ToolExecutionGateway`. Non-read-only tools require explicit approval.

## GitService
`init`, `clone`, `status`, `add`, `commit`, `push(confirmed)`, `pull`, `fetch`, `branch`, `checkout`, `merge`, `diff`, `log`, `remote`.
Push is rejected unless explicit confirmation is supplied.

## TerminalService
`execute`, `cancel`, `state`. Commands run through the embedded backend with an executable allowlist and workspace-bound working directory.

## BuildService
`configure`, `build`, `clean`, `rebuild`, `cancel`.

## TestRunner
`discoverTests`, `runTests`, `stopTests`.

## Runtime
`CodeRunner.prepare`, `build`, `run`, `stop`, `status`.

## ProjectManager
`create`, `open`, `list`, `delete`.

## MemoryStore
`add`, `list`, `remove`, `clear`.

## Voice
`SpeechToText.start`, `stop`, `release`; `TextToSpeechEngine.speak`, `stop`, `release`.

No endpoint silently exposes raw low-level exceptions to the UI. Implementations map failures into structured result/error models where applicable.
