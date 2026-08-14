package com.sa.aidesktop.core.memory

data class MemoryEntry(val id:String,val type:MemoryType,val value:String,val createdAt:Long=System.currentTimeMillis())
enum class MemoryType{CONVERSATION,PROJECT,USER_PREFERENCE,TASK_HISTORY}
interface MemoryStore{fun add(entry:MemoryEntry):Boolean;fun list(type:MemoryType?=null):List<MemoryEntry>;fun remove(id:String):Boolean;fun clear(type:MemoryType?=null)}
class InMemoryMemoryStore:MemoryStore{private val entries=linkedMapOf<String,MemoryEntry>();override fun add(entry:MemoryEntry)=entries.put(entry.id,entry)==null;override fun list(type:MemoryType?)=entries.values.filter{type==null||it.type==type};override fun remove(id:String)=entries.remove(id)!=null;override fun clear(type:MemoryType?){if(type==null)entries.clear()else entries.values.filter{it.type==type}.map{it.id}.forEach(entries::remove)}}
