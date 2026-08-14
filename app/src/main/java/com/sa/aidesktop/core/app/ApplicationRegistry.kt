package com.sa.aidesktop.core.app

data class ApplicationDescriptor(val id:String,val title:String,val version:String,val windowType:String)
class ApplicationRegistry(private val apps:MutableMap<String,ApplicationDescriptor> = linkedMapOf()){
    fun register(application:ApplicationDescriptor):Boolean{if(application.id.isBlank()||apps.containsKey(application.id))return false;apps[application.id]=application;return true}
    fun unregister(id:String)=apps.remove(id)!=null
    fun find(id:String)=apps[id]
    fun all(): List<ApplicationDescriptor> = apps.values.toList()
}
