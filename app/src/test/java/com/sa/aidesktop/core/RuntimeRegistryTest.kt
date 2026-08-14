package com.sa.aidesktop.core
import com.sa.aidesktop.core.runtime.*
import org.junit.Assert.*
import org.junit.Test
class RuntimeRegistryTest { @Test fun registryIsExtensible(){ assertTrue(RuntimeRegistry.runners().map{it.language}.containsAll(listOf("Python","Java","JavaScript","C","C++"))) } }
