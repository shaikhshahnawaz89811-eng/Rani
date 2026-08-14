package com.sa.aidesktop.core.git
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
class GitServiceTest {
 @Test fun blankCommitRejected()=runBlocking { val r=DemoGitService().commit(""); assertTrue(r is GitResult.Failure) }
 @Test fun statusHasBranch()=runBlocking { val r=DemoGitService().status() as GitResult.Success; assertEquals("main",r.value.branch) }
 @Test fun addRequiresPaths()=runBlocking { assertTrue(DemoGitService().add(emptyList()) is GitResult.Failure) }
 @Test fun pushRequiresConfirmation()=runBlocking { assertTrue(DemoGitService().push() is GitResult.Failure); assertTrue(DemoGitService().push(true) is GitResult.Success) }
}
