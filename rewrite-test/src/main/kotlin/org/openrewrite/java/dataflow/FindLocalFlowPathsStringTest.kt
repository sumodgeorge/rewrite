/*
 * Copyright 2022 the original author or authors.
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
package org.openrewrite.java.dataflow

import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.openrewrite.Cursor
import org.openrewrite.java.tree.Expression
import org.openrewrite.java.tree.J
import org.openrewrite.test.RecipeSpec
import org.openrewrite.test.RewriteTest

@Suppress("FunctionName")
interface FindLocalFlowPathsStringTest: RewriteTest {
    override fun defaults(spec: RecipeSpec) {
        spec.recipe(RewriteTest.toRecipe {
            FindLocalFlowPaths(object : LocalFlowSpec<Expression, Expression>() {
                override fun isSource(expr: Expression, cursor: Cursor) =
                    when(expr) {
                        is J.Literal -> expr.value == "42"
                        is J.MethodInvocation -> expr.name.simpleName == "source"
                        else -> false
                    }

                override fun isSink(expr: Expression, cursor: Cursor) =
                    true
            })
        })
        spec.expectedCyclesThatMakeChanges(1).cycles(1)
    }

    @Test
    fun `transitive assignment from literal`() = rewriteRun(
        java(
            """
                class Test {
                    void test() {
                        String n = "42";
                        String o = n;
                        System.out.println(o);
                        String p = o;
                    }
                }
            """,
            """
                class Test {
                    void test() {
                        String n = /*~~>*/"42";
                        String o = /*~~>*/n;
                        System.out.println(/*~~>*/o);
                        String p = /*~~>*/o;
                    }
                }
            """
        )
    )

    @Test
    fun `transitive assignment from source method`() = rewriteRun(
        { spec -> spec.expectedCyclesThatMakeChanges(1).cycles(1) },
        java(
            """
                class Test {
                    String source() {
                        return null;
                    }

                    void test() {
                        String n = source();
                        String o = n;
                        System.out.println(o);
                        String p = o;
                    }
                }
            """,
            """
                class Test {
                    String source() {
                        return null;
                    }

                    void test() {
                        String n = /*~~>*/source();
                        String o = /*~~>*/n;
                        System.out.println(/*~~>*/o);
                        String p = /*~~>*/o;
                    }
                }
            """
        )
    )

    @Test
    fun `taint flow via append is not data flow`() = rewriteRun(
        { spec -> spec.expectedCyclesThatMakeChanges(1).cycles(1) },
        java(
            """
                class Test {
                    void test() {
                        String n = "42";
                        String o = n + '/';
                        System.out.println(o);
                        String p = o;
                    }
                }
            """,
            """
                class Test {
                    void test() {
                        String n = /*~~>*/"42";
                        String o = /*~~>*/n + '/';
                        System.out.println(o);
                        String p = o;
                    }
                }
                """
        )
    )

    @Test
    fun `taint flow is not data flow but it is tracked to call site`() = rewriteRun(
        { spec -> spec.expectedCyclesThatMakeChanges(1).cycles(1) },
        java(
            """
                class Test {
                    void test() {
                        String n = "42";
                        String o = n.toString() + '/';
                        System.out.println(o);
                        String p = o;
                    }
                }
            """,
            """
                class Test {
                    void test() {
                        String n = /*~~>*/"42";
                        String o = /*~~>*//*~~>*/n.toString() + '/';
                        System.out.println(o);
                        String p = o;
                    }
                }
                """
        )
    )

    @Test
    fun `taint flow via constructor call is not data flow`() = rewriteRun(
        { spec -> spec.expectedCyclesThatMakeChanges(1).cycles(1) },
        java(
            """
                class Test {
                    void test() {
                        String n = "42";
                        java.io.File o = new java.io.File(n);
                        System.out.println(o);
                    }
                }
            """,
            """
                class Test {
                    void test() {
                        String n = /*~~>*/"42";
                        java.io.File o = new java.io.File(/*~~>*/n);
                        System.out.println(o);
                    }
                }
                """
        )
    )

    @Test
    fun `the source is also a sink simple`() = rewriteRun(
        { spec -> spec.expectedCyclesThatMakeChanges(1).cycles(1) },
        java(
            """
                class Test {
                    String source() {
                        return null;
                    }
                    void sink(Object any) {
                        // do nothing
                    }
                    void test() {
                        sink(source());
                    }
                }
            """,
            """
                class Test {
                    String source() {
                        return null;
                    }
                    void sink(Object any) {
                        // do nothing
                    }
                    void test() {
                        sink(/*~~>*/source());
                    }
                }
            """
        )
    )

    @Test
    fun `the source as a literal is also a sink`() = rewriteRun(
        { spec -> spec.expectedCyclesThatMakeChanges(1).cycles(1) },
        java(
            """
                class Test {
                    void sink(Object any) {
                        // do nothing
                    }
                    void test() {
                        sink("42");
                    }
                }
            """,
            """
                class Test {
                    void sink(Object any) {
                        // do nothing
                    }
                    void test() {
                        sink(/*~~>*/"42");
                    }
                }
            """
        )
    )

    @Test
    fun `the source is also a sink`() = rewriteRun(
        { spec -> spec.expectedCyclesThatMakeChanges(1).cycles(1) },
        java(
            """
                import java.util.Locale;
                class Test {
                    String source() {
                        return null;
                    }
                    void test() {
                        source();
                        source()
                            .toString();
                        source()
                            .toLowerCase(Locale.ROOT);
                        source()
                            .toString()
                            .toLowerCase(Locale.ROOT);
                    }
                }
            """,
        """
                import java.util.Locale;
                class Test {
                    String source() {
                        return null;
                    }
                    void test() {
                        /*~~>*/source();
                        /*~~>*//*~~>*/source()
                            .toString();
                        /*~~>*/source()
                            .toLowerCase(Locale.ROOT);
                        /*~~>*//*~~>*/source()
                            .toString()
                            .toLowerCase(Locale.ROOT);
                    }
                }
                """
        )
    )

    @Test
    fun `the source is also a sink double call chain`() = rewriteRun(
        { spec -> spec.expectedCyclesThatMakeChanges(1).cycles(1) },
        java(
            """
                class Test {
                    String source() {
                        return null;
                    }
                    void test() {
                        source()
                            .toString()
                            .toString();
                    }
                }
            """,
            """
                class Test {
                    String source() {
                        return null;
                    }
                    void test() {
                        /*~~>*//*~~>*//*~~>*/source()
                            .toString()
                            .toString();
                    }
                }
                """
        )
    )

    @Test
    fun `the source can be tracked through wrapped parentheses`() = rewriteRun(
        { spec -> spec.expectedCyclesThatMakeChanges(1).cycles(1) },
        java(
            """
                import java.util.Locale;
                class Test {
                    String source() {
                        return null;
                    }
                    void test() {
                        (
                            source()
                        ).toLowerCase(Locale.ROOT);
                        (
                            (
                                source()
                            )
                        ).toLowerCase(Locale.ROOT);
                        (
                            (Object) source()
                        ).equals(null);
                    }
                }
            """,
            """
                import java.util.Locale;
                class Test {
                    String source() {
                        return null;
                    }
                    void test() {
                        /*~~>*/(
                            /*~~>*/source()
                        ).toLowerCase(Locale.ROOT);
                        /*~~>*/(
                            /*~~>*/(
                                /*~~>*/source()
                            )
                        ).toLowerCase(Locale.ROOT);
                        /*~~>*/(
                            /*~~>*/(Object) /*~~>*/source()
                        ).equals(null);
                    }
                }
                """
        )
    )

    @Test
    fun `the source can be tracked through wrapped parentheses through casting`() = rewriteRun(
        { spec -> spec.expectedCyclesThatMakeChanges(1).cycles(1) },
        java(
            """
                import java.util.Locale;
                class Test {
                    String source() {
                        return null;
                    }
                    void test() {
                        (
                            (String)(
                                (Object) source()
                            )
                        ).toString();
                    }
                }
            """,
            """
                import java.util.Locale;
                class Test {
                    String source() {
                        return null;
                    }
                    void test() {
                        /*~~>*//*~~>*/(
                            /*~~>*/(String)/*~~>*/(
                                /*~~>*/(Object) /*~~>*/source()
                            )
                        ).toString();
                    }
                }
                """
        )
    )

    @Test
    fun `source is tracked when assigned in while loop control parentheses`() = rewriteRun(
        { spec -> spec.expectedCyclesThatMakeChanges(1).cycles(1) },
        java(
            """
                class Test {
                    String source() {
                        return null;
                    }
                    @SuppressWarnings("SillyAssignment")
                    void test() {
                        String a;
                        a = a;
                        while ((a = source()) != null) {
                            System.out.println(a);
                        }
                    }
                }
            """,
            """
            class Test {
                String source() {
                    return null;
                }
                @SuppressWarnings("SillyAssignment")
                void test() {
                    String a;
                    a = a;
                    while ((a = /*~~>*/source()) != null) {
                        System.out.println(/*~~>*/a);
                    }
                }
            }
                """
        )
    )

    @Test
    fun `source is tracked when assigned in do while loop control parentheses`() = rewriteRun(
        { spec -> spec.expectedCyclesThatMakeChanges(1).cycles(1) },
        java(
            """
                class Test {
                    String source() {
                        return null;
                    }
                    @SuppressWarnings("SillyAssignment")
                    void test() {
                        String a = null;
                        a = a;
                        do {
                            System.out.println(a);
                        } while ((a = source()) != null);
                    }
                }
            """,
            """
            class Test {
                String source() {
                    return null;
                }
                @SuppressWarnings("SillyAssignment")
                void test() {
                    String a = null;
                    a = a;
                    do {
                        System.out.println(/*~~>*/a);
                    } while ((a = /*~~>*/source()) != null);
                }
            }
                """
        )
    )

    @Test
    fun `source is tracked when assigned in for loop`() = rewriteRun(
        { spec -> spec.expectedCyclesThatMakeChanges(1).cycles(1) },
        java(
            """
                class Test {
                    String source(int i) {
                        return null;
                    }
                    @SuppressWarnings("SillyAssignment")
                    void test() {
                        String a = null;
                        a = a;
                        for (int i = 0; i < 10 && (a = source(i)) != null; i++) {
                            System.out.println(a);
                        }
                    }
                }
            """,
            """
            class Test {
                String source(int i) {
                    return null;
                }
                @SuppressWarnings("SillyAssignment")
                void test() {
                    String a = null;
                    a = a;
                    for (int i = 0; i < 10 && (a = /*~~>*/source(i)) != null; i++) {
                        System.out.println(/*~~>*/a);
                    }
                }
            }
                """
        )
    )

    @Test
    fun `reassignment of a variable breaks flow`() = rewriteRun(
        { spec -> spec.expectedCyclesThatMakeChanges(1).cycles(1) },
        java(
            """
                class Test {
                    void test() {
                        String n = "42";
                        System.out.println(n);
                        n = "100";
                        System.out.println(n);
                    }
                }
            """,
            """
                class Test {
                    void test() {
                        String n = /*~~>*/"42";
                        System.out.println(/*~~>*/n);
                        n = "100";
                        System.out.println(n);
                    }
                }
            """
        )
    )

    @Test
    fun `reassignment of a variable with existing value preserves flow`() = rewriteRun(
        { spec -> spec.expectedCyclesThatMakeChanges(1).cycles(1) },
        java(
            """
                class Test {
                    void test() {
                        String n = "42";
                        System.out.println(n);
                        n = n;
                        System.out.println(n);
                    }
                }
            """,
            """
                class Test {
                    void test() {
                        String n = /*~~>*/"42";
                        System.out.println(/*~~>*/n);
                        n = /*~~>*/n;
                        System.out.println(/*~~>*/n);
                    }
                }
            """
        )
    )

    @Test
    fun `reassignment of a variable with existing value wrapped in parentheses preserves flow`() = rewriteRun(
        { spec -> spec.expectedCyclesThatMakeChanges(1).cycles(1) },
        java(
            """
                class Test {
                    void test() {
                        String n = "42";
                        System.out.println(n);
                        (n) = n;
                        System.out.println(n);
                    }
                }
            """,
            """
                class Test {
                    void test() {
                        String n = /*~~>*/"42";
                        System.out.println(/*~~>*/n);
                        (n) = /*~~>*/n;
                        System.out.println(/*~~>*/n);
                    }
                }
            """
        )
    )

    @Test
    fun `a class name in a constructor call is not considered as a part of dataflow`() = rewriteRun(
        { spec -> spec.expectedCyclesThatMakeChanges(1).cycles(1) },
        java(
            """
                class Test {
                    class n {}

                    void test() {
                        String n = "42";
                        System.out.println(n);
                        n = new n().toString();
                        System.out.println(n);
                    }
                }
            """,
            """
                class Test {
                    class n {}

                    void test() {
                        String n = /*~~>*/"42";
                        System.out.println(/*~~>*/n);
                        n = new n().toString();
                        System.out.println(n);
                    }
                }
            """
        )
    )

    @Test
    fun `a class name in a constructor call on parent type is not considered as a part of dataflow`() = rewriteRun(
        { spec -> spec.expectedCyclesThatMakeChanges(1).cycles(1) },
        java(
            """
                class Test {
                    class n {}

                    void test() {
                        String n = "42";
                        System.out.println(n);
                        n = new Test.n().toString();
                        System.out.println(n);
                    }
                }
            """,
            """
                class Test {
                    class n {}

                    void test() {
                        String n = /*~~>*/"42";
                        System.out.println(/*~~>*/n);
                        n = new Test.n().toString();
                        System.out.println(n);
                    }
                }
            """
        )
    )

    @Test
    fun `a method name is not considered as a part of dataflow`() = rewriteRun(
        { spec -> spec.expectedCyclesThatMakeChanges(1).cycles(1) },
        java(
            """
                class Test {
                    String n() {
                        return null;
                    }

                    void test() {
                        String n = "42";
                        System.out.println(n);
                        n = n();
                        System.out.println(n);
                    }
                }
            """,
            """
                class Test {
                    String n() {
                        return null;
                    }

                    void test() {
                        String n = /*~~>*/"42";
                        System.out.println(/*~~>*/n);
                        n = n();
                        System.out.println(n);
                    }
                }
            """
        )
    )

    @Test
    fun `a class variable access is not considered as a part of dataflow`() = rewriteRun(
        { spec -> spec.expectedCyclesThatMakeChanges(1).cycles(1) },
        java(
            """
                class Test {
                    String n = "100";

                    void test() {
                        String n = "42";
                        System.out.println(n);
                        System.out.println(this.n);
                    }
                }
            """,
            """
                class Test {
                    String n = "100";

                    void test() {
                        String n = /*~~>*/"42";
                        System.out.println(/*~~>*/n);
                        System.out.println(this.n);
                    }
                }
            """
        )
    )

    @Test
    fun `a ternary operator is considered a data flow step`() = rewriteRun(
        { spec -> spec.expectedCyclesThatMakeChanges(1).cycles(1) },
        java(
            """
                class Test {

                    void test(boolean conditional) {
                        String n = conditional ? "42" : "100";
                        System.out.println(n);
                    }
                }
            """,
            """
                class Test {

                    void test(boolean conditional) {
                        String n = /*~~>*/conditional ? /*~~>*/"42" : "100";
                        System.out.println(/*~~>*/n);
                    }
                }
            """
        )
    )

    @Test
    fun `a ternary operator is considered a data flow step 2`() = rewriteRun(
        { spec -> spec.expectedCyclesThatMakeChanges(1).cycles(1) },
        java(
            """
                class Test {

                    void test(boolean conditional) {
                        String n = "42";
                        String m = conditional ? "100" : n;
                        System.out.println(m);
                    }
                }
            """,
            """
                class Test {

                    void test(boolean conditional) {
                        String n = /*~~>*/"42";
                        String m = /*~~>*/conditional ? "100" : /*~~>*/n;
                        System.out.println(/*~~>*/m);
                    }
                }
            """
        )
    )

    @Test
    fun `a ternary condition is not considered a data flow step`() = rewriteRun(
        java(
            """
                class Test {

                    Boolean source() {
                        return null;
                    }

                    void test(String other) {
                        String n = source() ? "102" : "100";
                        System.out.println(n);
                    }
                }
            """,
            """
                class Test {

                    Boolean source() {
                        return null;
                    }

                    void test(String other) {
                        String n = /*~~>*/source() ? "102" : "100";
                        System.out.println(n);
                    }
                }
            """
        )
    )

    @Test
    fun `Objects requireNotNull is a valid flow step`() = rewriteRun(
        java(
            """
                import java.util.Objects;
                @SuppressWarnings({"ObviousNullCheck", "RedundantSuppression"})
                class Test {
                    void test() {
                        String n = Objects.requireNonNull("42");
                        String o = n;
                        System.out.println(Objects.requireNonNull(o));
                        String p = o;
                    }
                }
            """,
            """
                import java.util.Objects;
                @SuppressWarnings({"ObviousNullCheck", "RedundantSuppression"})
                class Test {
                    void test() {
                        String n = /*~~>*/Objects.requireNonNull(/*~~>*/"42");
                        String o = /*~~>*/n;
                        System.out.println(/*~~>*/Objects.requireNonNull(/*~~>*/o));
                        String p = /*~~>*/o;
                    }
                }
            """
        )
    )

}
