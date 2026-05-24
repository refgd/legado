package io.legado.app.service

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class HttpReadAloudServiceExecutorTest {

    @Test
    fun downloadExecutorIsOwnedByHttpReadAloudService() {
        val future = httpReadAloudDownloadExecutor.submit<String> {
            Thread.currentThread().name
        }

        assertTrue(future.get(5, TimeUnit.SECONDS).startsWith("legado-http-read-aloud-"))
    }
}
