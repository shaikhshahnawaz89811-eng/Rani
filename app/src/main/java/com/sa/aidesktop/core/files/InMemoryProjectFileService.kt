package com.sa.aidesktop.core.files

class InMemoryProjectFileService : FileService {
    private val files = linkedMapOf(
        "src/main.py" to "print('hello')",
        "src/utils.py" to "def clamp(value, low, high): return max(low, min(value, high))",
        "tests/test_main.py" to "def test_add(): assert 2 + 3 == 5",
        "README.md" to "# MyProject",
        "requirements.txt" to "# runtime dependencies"
    )
    private val folders = linkedSetOf("src", "tests", "assets")
    private fun norm(path: String) = path.trim().trim('/')
    private fun valid(path: String) = path.isNotBlank() && !path.split('/').any { it.isBlank() || it == "." || it == ".." }
    private fun parent(path: String) = path.substringBeforeLast('/', "")
    private fun kind(path: String) = when {
        path.endsWith(".py", true) -> FileKind.PYTHON
        path.endsWith(".kt", true) -> FileKind.KOTLIN
        path.endsWith(".java", true) -> FileKind.JAVA
        path.endsWith(".js", true) -> FileKind.JAVASCRIPT
        path.endsWith(".c", true) -> FileKind.C
        path.endsWith(".cpp", true) -> FileKind.CPP
        path.endsWith(".json", true) -> FileKind.JSON
        path.endsWith(".xml", true) -> FileKind.XML
        path.endsWith(".md", true) -> FileKind.MARKDOWN
        path.endsWith(".txt", true) -> FileKind.TEXT
        else -> FileKind.OTHER
    }
    override fun projectTree(): ProjectFile = tree("")
    private fun tree(prefix: String): ProjectFile {
        val childrenPaths = (folders.filter { parent(it) == prefix } + files.keys.filter { parent(it) == prefix }).distinct().sorted()
        val children = childrenPaths.map { if (folders.contains(it)) tree(it) else ProjectFile(it, it.substringAfterLast('/'), kind(it), sizeBytes = files[it].orEmpty().toByteArray().size.toLong()) }
        return ProjectFile(prefix, if (prefix.isEmpty()) "MyProject" else prefix.substringAfterLast('/'), FileKind.FOLDER, children, children.sumOf { it.sizeBytes })
    }
    override fun read(path: String): FileResult<String> { val p=norm(path); if(p.isBlank() || !valid(p)) return FileResult.fail(FileError.InvalidPath); return files[p]?.let{FileResult.ok(it)} ?: if(folders.contains(p)) FileResult.fail(FileError.IsDirectory) else FileResult.fail(FileError.NotFound) }
    override fun write(path: String, content: String): FileResult<Unit> { val p=norm(path); if(!valid(p)) return FileResult.fail(FileError.InvalidPath); if(folders.contains(p)) return FileResult.fail(FileError.IsDirectory); files[p]=content; return FileResult.ok(Unit) }
    override fun createFile(path: String, content: String): FileResult<Unit> { val p=norm(path); if(!valid(p)) return FileResult.fail(FileError.InvalidPath); if(files.containsKey(p)||folders.contains(p)) return FileResult.fail(FileError.AlreadyExists); return write(p,content) }
    override fun createFolder(path: String): FileResult<Unit> { val p=norm(path); if(!valid(p)) return FileResult.fail(FileError.InvalidPath); if(files.containsKey(p)||folders.contains(p)) return FileResult.fail(FileError.AlreadyExists); folders.add(p); return FileResult.ok(Unit) }
    override fun rename(path: String,newName:String):FileResult<String>{ val p=norm(path); if(newName.isBlank()||newName.contains('/')||newName.contains('\\')) return FileResult.fail(FileError.InvalidName("Invalid name")); if(!files.containsKey(p)&&!folders.contains(p)) return FileResult.fail(FileError.NotFound); val np=parent(p).let{if(it.isBlank())newName else "$it/$newName"}; if(files.containsKey(np)||folders.contains(np)) return FileResult.fail(FileError.AlreadyExists); if(files.containsKey(p)){val c=files.remove(p)!!;files[np]=c}else{val affected=folders.filter{it==p||it.startsWith("$p/")};affected.sortedByDescending{it.length}.forEach{folders.remove(it)};affected.forEach{folders.add(np+it.removePrefix(p))};val affectedFiles=files.filterKeys{it.startsWith("$p/")};affectedFiles.forEach{(k,v)->files.remove(k);files[np+k.removePrefix(p)]=v};folders.add(np)};return FileResult.ok(np)}
    override fun delete(path:String):FileResult<Unit>{val p=norm(path);if(files.remove(p)!=null)return FileResult.ok(Unit);if(folders.contains(p)){if(files.keys.any{it.startsWith("$p/")}||folders.any{it!=p&&it.startsWith("$p/")})return FileResult.fail(FileError.DirectoryNotEmpty);folders.remove(p);return FileResult.ok(Unit)};return FileResult.fail(FileError.NotFound)}
    override fun copy(source:String,destination:String):FileResult<Unit>{val s=norm(source);val d=norm(destination);if(files.containsKey(s)){if(files.containsKey(d)||folders.contains(d))return FileResult.fail(FileError.AlreadyExists);files[d]=files[s]!!;return FileResult.ok(Unit)};if(!folders.contains(s))return FileResult.fail(FileError.NotFound);if(files.containsKey(d)||folders.contains(d)||d.startsWith("$s/"))return FileResult.fail(FileError.InvalidPath);folders.add(d);folders.filter{it.startsWith("$s/")}.forEach{folders.add(d+it.removePrefix(s))};files.filterKeys{it.startsWith("$s/")}.forEach{(k,v)->files[d+k.removePrefix(s)]=v};return FileResult.ok(Unit)}
    override fun move(source:String,destination:String):FileResult<Unit>{val c=copy(source,destination);if(!c.isSuccess)return c;return deleteTree(norm(source))}
    private fun deleteTree(p:String):FileResult<Unit>{files.keys.filter{it==p||it.startsWith("$p/")}.toList().forEach{files.remove(it)};folders.filter{it==p||it.startsWith("$p/")}.toList().forEach{folders.remove(it)};return FileResult.ok(Unit)}
    override fun exists(path:String)=FileResult.ok(files.containsKey(norm(path))||folders.contains(norm(path)))
    override fun listDirectory(path:String):FileResult<List<ProjectFile>>{val p=norm(path); if(p.isNotEmpty()&&!valid(p)) return FileResult.fail(FileError.InvalidPath);if(p.isNotEmpty()&&!folders.contains(p))return FileResult.fail(if(files.containsKey(p))FileError.NotDirectory else FileError.NotFound);return FileResult.ok((folders.filter{parent(it)==p}+files.keys.filter{parent(it)==p}).distinct().sorted().map{if(folders.contains(it))tree(it)else ProjectFile(it,it.substringAfterLast('/'),kind(it),sizeBytes=files[it].orEmpty().toByteArray().size.toLong())})}
    override fun search(query:String):FileResult<List<ProjectFile>>{val q=query.trim();if(q.isEmpty())return FileResult.ok(emptyList());return FileResult.ok((files.keys.filter{it.contains(q,true)}+folders.filter{it.contains(q,true)}).distinct().take(200).map{if(folders.contains(it))tree(it)else ProjectFile(it,it.substringAfterLast('/'),kind(it),sizeBytes=files[it].orEmpty().toByteArray().size.toLong())})}
}
