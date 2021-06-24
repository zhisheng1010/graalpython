/*
 * Copyright (c) 2021, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.graal.python.builtins.modules;

import static com.oracle.graal.python.runtime.exception.PythonErrorType.TypeError;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.oracle.graal.python.PythonLanguage;
import com.oracle.graal.python.builtins.Builtin;
import com.oracle.graal.python.builtins.CoreFunctions;
import com.oracle.graal.python.builtins.PythonBuiltinClassType;
import com.oracle.graal.python.builtins.PythonBuiltins;
import com.oracle.graal.python.builtins.objects.cext.common.LoadCExtException.ApiInitException;
import com.oracle.graal.python.builtins.objects.cext.common.LoadCExtException.ImportException;
import com.oracle.graal.python.builtins.objects.cext.hpy.GraalHPyContext;
import com.oracle.graal.python.builtins.objects.cext.hpy.GraalHPyDebugContext;
import com.oracle.graal.python.builtins.objects.cext.hpy.GraalHPyHandle;
import com.oracle.graal.python.builtins.objects.cext.hpy.PDebugHandle;
import com.oracle.graal.python.builtins.objects.function.PKeyword;
import com.oracle.graal.python.builtins.objects.list.PList;
import com.oracle.graal.python.nodes.ErrorMessages;
import com.oracle.graal.python.nodes.function.PythonBuiltinBaseNode;
import com.oracle.graal.python.nodes.function.PythonBuiltinNode;
import com.oracle.graal.python.nodes.function.builtins.PythonUnaryBuiltinNode;
import com.oracle.graal.python.nodes.function.builtins.PythonVarargsBuiltinNode;
import com.oracle.graal.python.runtime.ExecutionContext.IndirectCallContext;
import com.oracle.graal.python.runtime.PythonContext;
import com.oracle.graal.python.runtime.object.PythonObjectFactory;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.CachedLanguage;
import com.oracle.truffle.api.dsl.GenerateNodeFactory;
import com.oracle.truffle.api.dsl.NodeFactory;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;

@CoreFunctions(defineModule = GraalHPyDebugModuleBuiltins.HPY_DEBUG)
@GenerateNodeFactory
public class GraalHPyDebugModuleBuiltins extends PythonBuiltins {

    public static final String HPY_DEBUG = "_hpy_debug";

    @Override
    protected List<? extends NodeFactory<? extends PythonBuiltinBaseNode>> getNodeFactories() {
        return GraalHPyDebugModuleBuiltinsFactory.getFactories();
    }

    /**
     * In CPython, the {@code hpy.debug} module is implemented as HPy module. This means, whenever
     * you try to load it then the HPy API is initialized automatically. So, if someone is up to use
     * this module, we will initialize the HPy API.
     */
    static GraalHPyDebugContext getHPyDebugContext(VirtualFrame frame, PythonLanguage language, PythonBuiltinBaseNode node) {
        PythonContext context = node.getContext();
        Object state = IndirectCallContext.enter(frame, language, context, node);
        try {
            GraalHPyContext.ensureHPyWasLoaded(node, context, HPY_DEBUG, "");
        } catch (ApiInitException ie) {
            throw ie.reraise(node.getConstructAndRaiseNode(), frame);
        } catch (ImportException ie) {
            throw ie.reraise(node.getConstructAndRaiseNode(), frame);
        } catch (IOException e) {
            throw node.getConstructAndRaiseNode().raiseOSError(frame, e);
        } finally {
            IndirectCallContext.exit(frame, language, context, state);
        }
        assert context.hasHPyContext();
        return context.getHPyDebugContext();
    }

    @Builtin(name = "new_generation")
    @GenerateNodeFactory
    abstract static class HPyDebugNewGenerationNode extends PythonBuiltinNode {
        @Specialization
        int doGeneric(VirtualFrame frame,
                        @CachedLanguage PythonLanguage language) {
            GraalHPyDebugContext hpyDebugContext = getHPyDebugContext(frame, language, this);
            return hpyDebugContext.newGeneration();
        }
    }

    @Builtin(name = "get_open_handles", minNumOfPositionalArgs = 1)
    @GenerateNodeFactory
    abstract static class HPyDebugGetOpenHandlesNode extends PythonUnaryBuiltinNode {
        @Specialization
        PList doInt(VirtualFrame frame, int generation,
                        @CachedLanguage PythonLanguage language) {
            GraalHPyDebugContext hpyDebugContext = getHPyDebugContext(frame, language, this);
            Object[] openHandles = getOpenDebugHandles(hpyDebugContext, generation);
            return factory().createList(openHandles);
        }

        @TruffleBoundary
        private static Object[] getOpenDebugHandles(GraalHPyDebugContext debugContext, int generation) {
            ArrayList<GraalHPyHandle> openHandles = debugContext.getOpenHandles(generation);
            int n = openHandles.size();
            Object[] result = new Object[n];
            PythonObjectFactory factory = PythonObjectFactory.getUncached();
            // do reverse order to match order expected by HPy tests
            for (int i = 0; i < n; i++) {
                result[n - 1 - i] = factory.createDebugHandle(openHandles.get(i));
            }
            return result;
        }
    }

    @Builtin(name = "DebugHandle", minNumOfPositionalArgs = 1, constructsClass = PythonBuiltinClassType.DebugHandle, takesVarArgs = true, takesVarKeywordArgs = true)
    @GenerateNodeFactory
    abstract static class DebugHandleNode extends PythonVarargsBuiltinNode {

        @Override
        public Object varArgExecute(VirtualFrame frame, Object self, Object[] arguments, PKeyword[] keywords) throws VarargsBuiltinDirectInvocationNotSupported {
            if (arguments.length >= 1) {
                return doGeneric(arguments[0], null, null);
            }
            CompilerDirectives.transferToInterpreterAndInvalidate();
            throw new VarargsBuiltinDirectInvocationNotSupported();
        }

        @Specialization
        @SuppressWarnings("unused")
        PDebugHandle doGeneric(Object cls, Object[] args, PKeyword[] kwargs) {
            throw raise(TypeError, ErrorMessages.CANNOT_CREATE_INSTANCES, "DebugHandle");
        }
    }

}