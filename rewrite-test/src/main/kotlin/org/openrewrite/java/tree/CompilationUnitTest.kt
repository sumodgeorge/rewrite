/*
 * Copyright 2020 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite.java.tree

import org.junit.jupiter.api.Test
import org.openrewrite.Issue
import org.openrewrite.java.JavaParser
import org.openrewrite.java.JavaTreeTest
import org.openrewrite.java.JavaTreeTest.NestingLevel.CompilationUnit

interface CompilationUnitTest : JavaTreeTest {

    @Issue("https://github.com/openrewrite/rewrite/issues/1192")
    @Test
    fun emptyJavaFile(jp: JavaParser) {
        jp.parse(executionContext, "")
    }

    @Test
    fun imports(jp: JavaParser) = assertParsePrintAndProcess(
        jp, CompilationUnit, """
            import java.util.List;
            import java.io.*;
            public class A {}
        """
    )

    @Test
    fun packageAndComments(jp: JavaParser) = assertParsePrintAndProcess(
        jp, CompilationUnit, """
            /* Comment */
            package a;
            import java.util.List;
            
            public class A { }
            // comment
        """
    )
}
