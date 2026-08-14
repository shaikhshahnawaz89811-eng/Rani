package com.sa.aidesktop.core

import com.sa.aidesktop.core.files.*
import org.junit.Assert.*
import org.junit.Test

class FileServiceEndpointTest{
 @Test fun invalidAndDuplicateOperationsReturnErrors(){val fs=InMemoryProjectFileService();assertTrue(fs.createFile("../bad").error is FileError.InvalidPath);assertTrue(fs.createFile("src/main.py").error is FileError.AlreadyExists)}
 @Test fun renameAndExistsWork(){val fs=InMemoryProjectFileService();assertTrue(fs.createFile("src/a.txt","x").isSuccess);val r=fs.rename("src/a.txt","b.txt");assertTrue(r.isSuccess);assertTrue(fs.exists("src/b.txt").value==true);assertTrue(fs.read("src/a.txt").error is FileError.NotFound)}
}
