/*
 * Copyright (c) 2018, 2019, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.js.builtins;

import static com.oracle.truffle.js.runtime.util.BufferUtil.asBaseBuffer;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.Reader;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.Map;
import java.util.StringTokenizer;

import javax.script.Bindings;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleFile;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.ImportStatic;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.io.TruffleProcessBuilder;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.profiles.BranchProfile;
import com.oracle.truffle.api.profiles.ConditionProfile;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;
import com.oracle.truffle.js.builtins.GlobalBuiltinsFactory.GlobalNashornExtensionParseToJSONNodeGen;
import com.oracle.truffle.js.builtins.GlobalBuiltinsFactory.GlobalScriptingEXECNodeGen;
import com.oracle.truffle.js.builtins.GlobalBuiltinsFactory.JSGlobalDecodeURINodeGen;
import com.oracle.truffle.js.builtins.GlobalBuiltinsFactory.JSGlobalEncodeURINodeGen;
import com.oracle.truffle.js.builtins.GlobalBuiltinsFactory.JSGlobalExitNodeGen;
import com.oracle.truffle.js.builtins.GlobalBuiltinsFactory.JSGlobalImportScriptEngineGlobalBindingsNodeGen;
import com.oracle.truffle.js.builtins.GlobalBuiltinsFactory.JSGlobalIndirectEvalNodeGen;
import com.oracle.truffle.js.builtins.GlobalBuiltinsFactory.JSGlobalIsFiniteNodeGen;
import com.oracle.truffle.js.builtins.GlobalBuiltinsFactory.JSGlobalIsNaNNodeGen;
import com.oracle.truffle.js.builtins.GlobalBuiltinsFactory.JSGlobalLoadNodeGen;
import com.oracle.truffle.js.builtins.GlobalBuiltinsFactory.JSGlobalLoadWithNewGlobalNodeGen;
import com.oracle.truffle.js.builtins.GlobalBuiltinsFactory.JSGlobalParseFloatNodeGen;
import com.oracle.truffle.js.builtins.GlobalBuiltinsFactory.JSGlobalParseIntNodeGen;
import com.oracle.truffle.js.builtins.GlobalBuiltinsFactory.JSGlobalPrintNodeGen;
import com.oracle.truffle.js.builtins.GlobalBuiltinsFactory.JSGlobalReadBufferNodeGen;
import com.oracle.truffle.js.builtins.GlobalBuiltinsFactory.JSGlobalReadFullyNodeGen;
import com.oracle.truffle.js.builtins.GlobalBuiltinsFactory.JSGlobalReadLineNodeGen;
import com.oracle.truffle.js.builtins.GlobalBuiltinsFactory.JSGlobalUnEscapeNodeGen;
import com.oracle.truffle.js.builtins.helper.FloatParser;
import com.oracle.truffle.js.builtins.helper.StringEscape;
import com.oracle.truffle.js.lang.JavaScriptLanguage;
import com.oracle.truffle.js.nodes.JSGuards;
import com.oracle.truffle.js.nodes.JavaScriptNode;
import com.oracle.truffle.js.nodes.ScriptNode;
import com.oracle.truffle.js.nodes.access.JSConstantNode;
import com.oracle.truffle.js.nodes.cast.JSToDoubleNode;
import com.oracle.truffle.js.nodes.cast.JSToInt32Node;
import com.oracle.truffle.js.nodes.cast.JSToNumberNode;
import com.oracle.truffle.js.nodes.cast.JSToStringNode;
import com.oracle.truffle.js.nodes.cast.JSTrimWhitespaceNode;
import com.oracle.truffle.js.nodes.function.EvalNode;
import com.oracle.truffle.js.nodes.function.JSBuiltin;
import com.oracle.truffle.js.nodes.function.JSBuiltinNode;
import com.oracle.truffle.js.nodes.function.JSLoadNode;
import com.oracle.truffle.js.runtime.BigInt;
import com.oracle.truffle.js.runtime.Boundaries;
import com.oracle.truffle.js.runtime.Errors;
import com.oracle.truffle.js.runtime.Evaluator;
import com.oracle.truffle.js.runtime.ExitException;
import com.oracle.truffle.js.runtime.JSConsoleUtil;
import com.oracle.truffle.js.runtime.JSContext;
import com.oracle.truffle.js.runtime.JSErrorType;
import com.oracle.truffle.js.runtime.JSException;
import com.oracle.truffle.js.runtime.JSRealm;
import com.oracle.truffle.js.runtime.JSRuntime;
import com.oracle.truffle.js.runtime.JSTruffleOptions;
import com.oracle.truffle.js.runtime.LargeInteger;
import com.oracle.truffle.js.runtime.Symbol;
import com.oracle.truffle.js.runtime.builtins.BuiltinEnum;
import com.oracle.truffle.js.runtime.builtins.JSArgumentsObject;
import com.oracle.truffle.js.runtime.builtins.JSArrayBuffer;
import com.oracle.truffle.js.runtime.builtins.JSFunction;
import com.oracle.truffle.js.runtime.builtins.JSGlobalObject;
import com.oracle.truffle.js.runtime.builtins.JSURLDecoder;
import com.oracle.truffle.js.runtime.builtins.JSURLEncoder;
import com.oracle.truffle.js.runtime.objects.JSAttributes;
import com.oracle.truffle.js.runtime.objects.JSObject;
import com.oracle.truffle.js.runtime.objects.JSObjectUtil;
import com.oracle.truffle.js.runtime.objects.Null;
import com.oracle.truffle.js.runtime.objects.PropertyProxy;
import com.oracle.truffle.js.runtime.objects.Undefined;
import com.oracle.truffle.js.runtime.truffleinterop.JSInteropUtil;

/**
 * Contains builtins for the global object.
 */
public class GlobalBuiltins extends JSBuiltinsContainer.SwitchEnum<GlobalBuiltins.Global> {

    protected GlobalBuiltins() {
        super(JSGlobalObject.CLASS_NAME, Global.class);
    }

    public enum Global implements BuiltinEnum<Global> {
        isNaN(1),
        isFinite(1),
        parseFloat(1),
        parseInt(2),
        encodeURI(1),
        encodeURIComponent(1),
        decodeURI(1),
        decodeURIComponent(1),
        eval(1),

        // Annex B
        escape(1),
        unescape(1);

        private final int length;

        Global(int length) {
            this.length = length;
        }

        @Override
        public int getLength() {
            return length;
        }

        @Override
        public boolean isAnnexB() {
            return EnumSet.of(escape, unescape).contains(this);
        }
    }

    @Override
    protected Object createNode(JSContext context, JSBuiltin builtin, boolean construct, boolean newTarget, Global builtinEnum) {
        switch (builtinEnum) {
            case isNaN:
                return JSGlobalIsNaNNodeGen.create(context, builtin, args().fixedArgs(1).createArgumentNodes(context));
            case isFinite:
                return JSGlobalIsFiniteNodeGen.create(context, builtin, args().fixedArgs(1).createArgumentNodes(context));
            case parseFloat:
                return JSGlobalParseFloatNodeGen.create(context, builtin, args().fixedArgs(1).createArgumentNodes(context));
            case parseInt:
                return JSGlobalParseIntNodeGen.create(context, builtin, args().fixedArgs(2).createArgumentNodes(context));
            case encodeURI:
                return JSGlobalEncodeURINodeGen.create(context, builtin, true, args().fixedArgs(1).createArgumentNodes(context));
            case encodeURIComponent:
                return JSGlobalEncodeURINodeGen.create(context, builtin, false, args().fixedArgs(1).createArgumentNodes(context));
            case decodeURI:
                return JSGlobalDecodeURINodeGen.create(context, builtin, true, args().fixedArgs(1).createArgumentNodes(context));
            case decodeURIComponent:
                return JSGlobalDecodeURINodeGen.create(context, builtin, false, args().fixedArgs(1).createArgumentNodes(context));
            case eval:
                return JSGlobalIndirectEvalNodeGen.create(context, builtin, args().fixedArgs(1).createArgumentNodes(context));
            case escape:
                return JSGlobalUnEscapeNodeGen.create(context, builtin, false, args().fixedArgs(1).createArgumentNodes(context));
            case unescape:
                return JSGlobalUnEscapeNodeGen.create(context, builtin, true, args().fixedArgs(1).createArgumentNodes(context));
        }
        return null;
    }

    /**
     * Built-ins for js shell (for compatibility with e.g. d8).
     */
    public static final class GlobalShellBuiltins extends JSBuiltinsContainer.SwitchEnum<GlobalShellBuiltins.GlobalShell> {
        protected GlobalShellBuiltins() {
            super(JSGlobalObject.CLASS_NAME_SHELL_EXTENSIONS, GlobalShell.class);
        }

        public enum GlobalShell implements BuiltinEnum<GlobalShell> {
            quit(1),
            readline(1),
            read(1),
            readbuffer(1);

            private final int length;

            GlobalShell(int length) {
                this.length = length;
            }

            @Override
            public int getLength() {
                return length;
            }
        }

        @Override
        protected Object createNode(JSContext context, JSBuiltin builtin, boolean construct, boolean newTarget, GlobalShell builtinEnum) {
            switch (builtinEnum) {
                case quit:
                    return JSGlobalExitNodeGen.create(context, builtin, args().varArgs().createArgumentNodes(context));
                case readline:
                    return JSGlobalReadLineNodeGen.create(context, builtin, new JavaScriptNode[]{JSConstantNode.createUndefined()});
                case read:
                    return JSGlobalReadFullyNodeGen.create(context, builtin, args().fixedArgs(1).createArgumentNodes(context));
                case readbuffer:
                    return JSGlobalReadBufferNodeGen.create(context, builtin, args().fixedArgs(1).createArgumentNodes(context));
            }
            return null;
        }
    }

    /**
     * Built-ins for print.
     */
    public static final class GlobalPrintBuiltins extends JSBuiltinsContainer.SwitchEnum<GlobalPrintBuiltins.GlobalPrint> {
        protected GlobalPrintBuiltins() {
            super(JSGlobalObject.CLASS_NAME_PRINT_EXTENSIONS, GlobalPrint.class);
        }

        public enum GlobalPrint implements BuiltinEnum<GlobalPrint> {
            print(1),
            printErr(1);

            private final int length;

            GlobalPrint(int length) {
                this.length = length;
            }

            @Override
            public int getLength() {
                return length;
            }
        }

        @Override
        protected Object createNode(JSContext context, JSBuiltin builtin, boolean construct, boolean newTarget, GlobalPrint builtinEnum) {
            switch (builtinEnum) {
                case print:
                    return JSGlobalPrintNodeGen.create(context, builtin, false, args().varArgs().createArgumentNodes(context));
                case printErr:
                    return JSGlobalPrintNodeGen.create(context, builtin, true, args().varArgs().createArgumentNodes(context));
            }
            return null;
        }
    }

    /**
     * Built-ins for load.
     */
    public static final class GlobalLoadBuiltins extends JSBuiltinsContainer.SwitchEnum<GlobalLoadBuiltins.GlobalLoad> {
        protected GlobalLoadBuiltins() {
            super(JSGlobalObject.CLASS_NAME_LOAD_EXTENSIONS, GlobalLoad.class);
        }

        public enum GlobalLoad implements BuiltinEnum<GlobalLoad> {
            load(1),
            loadWithNewGlobal(1);

            private final int length;

            GlobalLoad(int length) {
                this.length = length;
            }

            @Override
            public int getLength() {
                return length;
            }
        }

        @Override
        protected Object createNode(JSContext context, JSBuiltin builtin, boolean construct, boolean newTarget, GlobalLoad builtinEnum) {
            switch (builtinEnum) {
                case load:
                    return JSGlobalLoadNodeGen.create(context, builtin, args().fixedArgs(1).varArgs().createArgumentNodes(context));
                case loadWithNewGlobal:
                    return JSGlobalLoadWithNewGlobalNodeGen.create(context, builtin, args().fixedArgs(1).varArgs().createArgumentNodes(context));
            }
            return null;
        }
    }

    public static final class GlobalNashornScriptingBuiltins extends JSBuiltinsContainer.SwitchEnum<GlobalNashornScriptingBuiltins.GlobalNashornScripting> {
        protected GlobalNashornScriptingBuiltins() {
            super(JSGlobalObject.CLASS_NAME_NASHORN_EXTENSIONS, GlobalNashornScripting.class);
        }

        /**
         * Manually added in initGlobalNashornExtensions or initGlobalScriptingExtensions.
         *
         * @see JSRealm
         */
        public enum GlobalNashornScripting implements BuiltinEnum<GlobalNashornScripting> {
            exit(1),
            quit(1),
            readLine(1),
            readFully(1),
            exec(1), // $EXEC
            parseToJSON(3),
            importScriptEngineGlobalBindings(1);

            private final int length;

            GlobalNashornScripting(int length) {
                this.length = length;
            }

            @Override
            public int getLength() {
                return length;
            }
        }

        @Override
        protected Object createNode(JSContext context, JSBuiltin builtin, boolean construct, boolean newTarget, GlobalNashornScripting builtinEnum) {
            switch (builtinEnum) {
                case exit:
                case quit:
                    return JSGlobalExitNodeGen.create(context, builtin, args().varArgs().createArgumentNodes(context));
                case readLine:
                    return JSGlobalReadLineNodeGen.create(context, builtin, args().fixedArgs(1).createArgumentNodes(context));
                case readFully:
                    return JSGlobalReadFullyNodeGen.create(context, builtin, args().fixedArgs(1).createArgumentNodes(context));
                case parseToJSON:
                    return GlobalNashornExtensionParseToJSONNodeGen.create(context, builtin, args().fixedArgs(3).createArgumentNodes(context));
                case exec:
                    return GlobalScriptingEXECNodeGen.create(context, builtin, args().fixedArgs(2).createArgumentNodes(context));
                case importScriptEngineGlobalBindings:
                    return JSGlobalImportScriptEngineGlobalBindingsNodeGen.create(context, builtin, args().fixedArgs(1).varArgs().createArgumentNodes(context));
            }
            return null;
        }
    }

    /**
     * For load("nashorn:parser.js") compatibility.
     */
    public abstract static class GlobalNashornExtensionParseToJSONNode extends JSBuiltinNode {
        public GlobalNashornExtensionParseToJSONNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @TruffleBoundary
        @Specialization
        protected String parseToJSON(Object code0, Object name0, Object location0) {
            String code = JSRuntime.toString(code0);
            String name = name0 == Undefined.instance ? "<unknown>" : JSRuntime.toString(name0);
            boolean location = JSRuntime.toBoolean(location0);
            return getContext().getEvaluator().parseToJSON(getContext(), code, name, location);
        }
    }

    /**
     * Implements $EXEC() in Nashorn scripting mode.
     */
    public abstract static class GlobalScriptingEXECNode extends JSBuiltinNode {
        public GlobalScriptingEXECNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization
        protected Object exec(Object cmd, Object input) {
            String cmdStr = JSRuntime.toString(cmd);
            String inputStr = input != Undefined.instance ? JSRuntime.toString(input) : null;
            return execIntl(cmdStr, inputStr);
        }

        @TruffleBoundary
        private Object execIntl(String cmd, String input) {
            JSRealm realm = getContext().getRealm();
            TruffleLanguage.Env env = realm.getEnv();
            DynamicObject globalObj = realm.getGlobalObject();
            StringTokenizer tok = new StringTokenizer(cmd);
            String[] cmds = new String[tok.countTokens()];
            for (int i = 0; tok.hasMoreTokens(); i++) {
                cmds[i] = tok.nextToken();
            }

            int exitCode = 0;
            String outStr = "";
            String errStr = "";
            Process process = null;
            IOException[] exception = new IOException[2];
            try {
                TruffleProcessBuilder builder = env.newProcessBuilder(cmds);

                Object envObj = JSObject.get(globalObj, "$ENV");
                if (JSGuards.isJSObject(envObj)) {
                    DynamicObject dynEnvObj = (DynamicObject) envObj;
                    Object pwd = JSObject.get(dynEnvObj, "PWD");
                    if (pwd != Undefined.instance) {
                        builder.directory(env.getPublicTruffleFile(JSRuntime.toString(pwd)));
                    }

                    builder.clearEnvironment(true);
                    for (String key : JSObject.enumerableOwnNames(dynEnvObj)) {
                        builder.environment(key, JSRuntime.toString(JSObject.get(dynEnvObj, key)));
                    }
                }

                process = builder.start();
                StringBuilder outBuffer = new StringBuilder();
                StringBuilder errBuffer = new StringBuilder();

                Thread outThread = captureThread(env, exception, 0, outBuffer, process.getInputStream());
                Thread errThread = captureThread(env, exception, 1, errBuffer, process.getErrorStream());

                outThread.start();
                errThread.start();

                try (OutputStreamWriter outputStream = new OutputStreamWriter(process.getOutputStream())) {
                    if (input != null) {
                        outputStream.write(input, 0, input.length());
                    }
                } catch (IOException ex) {
                }

                exitCode = process.waitFor();
                outThread.join();
                errThread.join();

                outStr = outBuffer.toString();
                errStr = errBuffer.toString();
            } catch (InterruptedException e) {
                if (process.isAlive()) {
                    process.destroy();
                }
                if (exitCode == 0) {
                    exitCode = process.exitValue();
                }
            } catch (IOException | SecurityException e) {
                throw Errors.createError(e.getMessage());
            }

            JSObject.set(globalObj, "$OUT", outStr);
            JSObject.set(globalObj, "$ERR", errStr);
            JSObject.set(globalObj, "$EXIT", exitCode);

            for (int i = 0; i < exception.length; i++) {
                if (exception[i] != null) {
                    throw Errors.createTypeError(exception[i].getMessage());
                }
            }
            return outStr;
        }

        private static Thread captureThread(TruffleLanguage.Env env, IOException[] exception, int exceptionIdx, StringBuilder outBuffer, InputStream stream) {
            return env.createThread(new Runnable() {
                @Override
                public void run() {
                    char[] buffer = new char[1024];
                    try (InputStreamReader inputStream = new InputStreamReader(stream)) {
                        for (int length; (length = inputStream.read(buffer, 0, buffer.length)) != -1;) {
                            outBuffer.append(buffer, 0, length);
                        }
                    } catch (IOException ex) {
                        exception[exceptionIdx] = ex;
                    }
                }
            }, env.newContextBuilder().build());
        }
    }

    private abstract static class JSGlobalOperation extends JSBuiltinNode {

        JSGlobalOperation(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Child private JSToStringNode toString1Node;

        protected final String toString1(Object target) {
            if (toString1Node == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                toString1Node = insert(JSToStringNode.create());
            }
            return toString1Node.executeString(target);
        }
    }

    /**
     * @throws SecurityException
     */
    static TruffleFile resolveRelativeFilePath(String path, TruffleLanguage.Env env) {
        CompilerAsserts.neverPartOfCompilation();
        TruffleFile file = env.getPublicTruffleFile(path);
        if (!file.isAbsolute() && !file.exists()) {
            TruffleFile f = tryResolveCallerRelativeFilePath(path, env);
            if (f != null) {
                return f;
            }
        }
        return file;
    }

    private static TruffleFile tryResolveCallerRelativeFilePath(String path, TruffleLanguage.Env env) {
        CompilerAsserts.neverPartOfCompilation();
        CallTarget caller = Truffle.getRuntime().getCallerFrame().getCallTarget();
        if (caller instanceof RootCallTarget) {
            SourceSection callerSourceSection = ((RootCallTarget) caller).getRootNode().getSourceSection();
            if (callerSourceSection != null && callerSourceSection.isAvailable()) {
                String callerPath = callerSourceSection.getSource().getPath();
                if (callerPath != null) {
                    TruffleFile callerFile = env.getPublicTruffleFile(callerPath);
                    if (callerFile.isAbsolute()) {
                        TruffleFile file = callerFile.resolveSibling(path).normalize();
                        if (file.isRegularFile()) {
                            return file;
                        }
                    }
                }
            }
        }
        return null;
    }

    public abstract static class JSLoadOperation extends JSGlobalOperation {
        public JSLoadOperation(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Child private JSLoadNode loadNode;

        protected static final String EVAL_OBJ_FILE_NAME = "name";
        protected static final String EVAL_OBJ_SOURCE = "script";

        // nashorn load pseudo URL prefixes
        private static final String LOAD_CLASSPATH = "classpath:";
        private static final String LOAD_FX = "fx:";
        private static final String LOAD_NASHORN = "nashorn:";
        // nashorn default paths
        private static final String RESOURCES_PATH = "resources/";
        private static final String FX_RESOURCES_PATH = "resources/fx/";
        private static final String NASHORN_BASE_PATH = "jdk/nashorn/internal/runtime/";
        private static final String NASHORN_PARSER_JS = "nashorn:parser.js";
        private static final String NASHORN_MOZILLA_COMPAT_JS = "nashorn:mozilla_compat.js";

        protected final Object runImpl(JSRealm realm, Source source) {
            if (loadNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                loadNode = insert(JSLoadNode.create(getContext()));
            }
            return loadNode.executeLoad(source, realm);
        }

        protected static ScriptNode loadStringImpl(JSContext ctxt, String name, String script) {
            CompilerAsserts.neverPartOfCompilation();
            long startTime = JSTruffleOptions.ProfileTime ? System.nanoTime() : 0L;
            try {
                return ctxt.getEvaluator().evalCompile(ctxt, script, name);
            } finally {
                if (JSTruffleOptions.ProfileTime) {
                    ctxt.getTimeProfiler().printElapsed(startTime, "parsing " + name);
                }
            }
        }

        @TruffleBoundary
        protected final Source sourceFromURL(URL url) {
            assert getContext().isOptionNashornCompatibilityMode() || getContext().isOptionLoadFromURL();
            try {
                return Source.newBuilder(JavaScriptLanguage.ID, url).name(url.getFile()).build();
            } catch (IOException | SecurityException e) {
                throw JSException.create(JSErrorType.EvalError, e.getMessage(), e, this);
            }
        }

        @TruffleBoundary
        protected final Source sourceFromFileName(String fileName, JSRealm realm) {
            try {
                return Source.newBuilder(JavaScriptLanguage.ID, realm.getEnv().getPublicTruffleFile(fileName)).name(fileName).build();
            } catch (IOException | SecurityException e) {
                throw JSException.create(JSErrorType.EvalError, e.getMessage(), e, this);
            }
        }

        @TruffleBoundary
        protected final Source sourceFromTruffleFile(TruffleFile file) {
            try {
                return Source.newBuilder(JavaScriptLanguage.ID, file).build();
            } catch (IOException | SecurityException e) {
                throw JSException.create(JSErrorType.EvalError, e.getMessage(), e, this);
            }
        }

        @TruffleBoundary
        protected static final String fileGetPath(File file) {
            return file.getPath();
        }

        @TruffleBoundary(transferToInterpreterOnException = false)
        protected Source sourceFromPath(String path, JSRealm realm) {
            Source source = null;
            JSContext ctx = getContext();
            if ((ctx.isOptionNashornCompatibilityMode() || ctx.isOptionLoadFromURL()) && path.indexOf(':') != -1) {
                source = sourceFromURI(path);
                if (source != null) {
                    return source;
                }
            }

            try {
                TruffleFile file = resolveRelativeFilePath(path, realm.getEnv());
                if (file.isRegularFile()) {
                    source = sourceFromTruffleFile(file);
                }
            } catch (SecurityException e) {
                throw Errors.createErrorFromException(e);
            }

            if (source == null) {
                throw cannotLoadScript(path);
            }
            return source;
        }

        private Source sourceFromURI(String resource) {
            if (JSTruffleOptions.SubstrateVM || !getContext().isOptionNashornCompatibilityMode() && !getContext().isOptionLoadFromURL()) {
                return null;
            }
            if ((resource.startsWith(LOAD_NASHORN) || resource.startsWith(LOAD_CLASSPATH) || resource.startsWith(LOAD_FX))) {
                return sourceFromResourceURL(resource);
            }

            try {
                URL url = new URL(resource);
                return sourceFromURL(url);
            } catch (MalformedURLException e) {
            }
            return null;
        }

        private Source sourceFromResourceURL(String resource) {
            assert getContext().isOptionNashornCompatibilityMode();
            InputStream stream = null;
            if (resource.startsWith(LOAD_NASHORN)) {
                if (resource.equals(NASHORN_PARSER_JS) || resource.equals(NASHORN_MOZILLA_COMPAT_JS)) {
                    stream = JSContext.class.getResourceAsStream(RESOURCES_PATH + resource.substring(LOAD_NASHORN.length()));
                }
            } else if (!JSTruffleOptions.SubstrateVM) {
                if (resource.startsWith(LOAD_CLASSPATH)) {
                    stream = ClassLoader.getSystemResourceAsStream(resource.substring(LOAD_CLASSPATH.length()));
                } else if (resource.startsWith(LOAD_FX)) {
                    stream = ClassLoader.getSystemResourceAsStream(NASHORN_BASE_PATH + FX_RESOURCES_PATH + resource.substring(LOAD_FX.length()));
                }
            }
            if (stream != null) {
                try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                    return Source.newBuilder(JavaScriptLanguage.ID, reader, resource).build();
                } catch (IOException | SecurityException e) {
                }
            }
            return null;
        }

        @TruffleBoundary(transferToInterpreterOnException = false)
        protected static JSException cannotLoadScript(Object script) {
            return Errors.createTypeError("Cannot load script: " + JSRuntime.safeToString(script));
        }
    }

    /**
     * Implementation of ECMAScript 5.1 15.1.2.4 isNaN() method.
     *
     */
    public abstract static class JSGlobalIsNaNNode extends JSBuiltinNode {

        public JSGlobalIsNaNNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization
        protected static boolean isNaN(@SuppressWarnings("unused") int value) {
            return false;
        }

        @Specialization
        protected static boolean isNaN(double value) {
            return Double.isNaN(value);
        }

        @Specialization(guards = "!isUndefined(value)")
        protected static boolean isNaN(Object value,
                        @Cached("create()") JSToDoubleNode toDoubleNode) {
            return isNaN(toDoubleNode.executeDouble(value));
        }

        @Specialization(guards = "isUndefined(value)")
        protected static boolean isNaN(@SuppressWarnings("unused") Object value) {
            return true;
        }
    }

    /**
     * Implementation of ECMAScript 5.1 15.1.2.5 isFinite() method.
     *
     */
    public abstract static class JSGlobalIsFiniteNode extends JSBuiltinNode {

        public JSGlobalIsFiniteNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization
        protected static boolean isFinite(@SuppressWarnings("unused") int value) {
            return true;
        }

        @Specialization
        protected static boolean isFinite(double value) {
            return !Double.isInfinite(value) && !Double.isNaN(value);
        }

        @Specialization(guards = "!isUndefined(value)")
        protected static boolean isFinite(Object value,
                        @Cached("create()") JSToDoubleNode toDoubleNode) {
            return isFinite(toDoubleNode.executeDouble(value));
        }

        @Specialization(guards = "isUndefined(value)")
        protected static boolean isFinite(@SuppressWarnings("unused") Object value) {
            return false;
        }
    }

    /**
     * Implementation of ECMAScript 5.1 15.1.2.3 parseFloat() method.
     */
    public abstract static class JSGlobalParseFloatNode extends JSGlobalOperation {
        private final BranchProfile exponentBranch = BranchProfile.create();
        @Child protected JSTrimWhitespaceNode trimWhitespaceNode;

        public JSGlobalParseFloatNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization
        protected int parseFloat(int value) {
            return value;
        }

        @Specialization
        protected double parseFloat(double value, @Cached("createBinaryProfile()") ConditionProfile negativeZero) {
            if (negativeZero.profile(JSRuntime.isNegativeZero(value))) {
                return 0;
            }
            return value;
        }

        @Specialization
        protected double parseFloat(@SuppressWarnings("unused") boolean value) {
            return Double.NaN;
        }

        @Specialization(guards = "isUndefined(value)")
        protected double parseFloatUndefined(@SuppressWarnings("unused") Object value) {
            return Double.NaN;
        }

        @Specialization(guards = "isJSNull(value)")
        protected double parseFloatNull(@SuppressWarnings("unused") Object value) {
            return Double.NaN;
        }

        @Specialization
        protected double parseFloat(String value) {
            return parseFloatIntl(value);
        }

        @Specialization(guards = {"!isJSNull(value)", "!isUndefined(value)"})
        protected double parseFloat(TruffleObject value) {
            return parseFloatIntl(toString1(value));
        }

        private double parseFloatIntl(String inputString) {
            String trimmedString = trimWhitespace(inputString);
            return parseFloatIntl2(trimmedString);
        }

        @TruffleBoundary
        private double parseFloatIntl2(String trimmedString) {
            if (trimmedString.startsWith(JSRuntime.INFINITY_STRING) || trimmedString.startsWith(JSRuntime.POSITIVE_INFINITY_STRING)) {
                return Double.POSITIVE_INFINITY;
            } else if (trimmedString.startsWith(JSRuntime.NEGATIVE_INFINITY_STRING)) {
                return Double.NEGATIVE_INFINITY;
            }
            try {
                FloatParser parser = new FloatParser(trimmedString, exponentBranch);
                return parser.getResult();
            } catch (NumberFormatException e) {
                return Double.NaN;
            }
        }

        protected String trimWhitespace(String s) {
            if (trimWhitespaceNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                trimWhitespaceNode = insert(JSTrimWhitespaceNode.create());
            }
            return trimWhitespaceNode.executeString(s);
        }
    }

    /**
     * Implementation of ECMAScript 5.1 15.1.2.2 parseInt() method.
     *
     */
    public abstract static class JSGlobalParseIntNode extends JSBuiltinNode {

        public JSGlobalParseIntNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Child private JSToInt32Node toInt32Node;
        private final BranchProfile needsNaN = BranchProfile.create();

        protected int toInt32(Object target) {
            if (toInt32Node == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                toInt32Node = insert(JSToInt32Node.create());
            }
            return toInt32Node.executeInt(target);
        }

        @Specialization(guards = "isUndefined(radix0)")
        protected int parseIntNoRadix(int thing, @SuppressWarnings("unused") Object radix0) {
            return thing;
        }

        @Specialization(guards = "!isUndefined(radix0)")
        protected Object parseInt(int thing, Object radix0,
                        @Cached("create()") BranchProfile needsRadixConversion) {
            int radix = toInt32(radix0);
            if (radix == 10 || radix == 0) {
                return thing;
            }
            if (radix < 2 || radix > 36) {
                needsNaN.enter();
                return Double.NaN;
            }
            needsRadixConversion.enter();
            return convertToRadix(thing, radix);
        }

        @Specialization(guards = {"hasRegularToStringInInt32Range(thing)", "isUndefined(radix0)"})
        protected int parseIntDoubleToInt(double thing, @SuppressWarnings("unused") Object radix0) {
            return (int) thing;
        }

        @Specialization(guards = {"hasRegularToString(thing)", "isUndefined(radix0)"})
        protected double parseIntNoRadix(double thing, @SuppressWarnings("unused") Object radix0) {
            return JSRuntime.truncateDouble2(thing);
        }

        // double specializations should not be used for numbers
        // that use a scientific notation when stringified
        // (parseInt(1e21) === parseInt('1e21') === 1)
        protected static boolean hasRegularToString(double value) {
            return (-1e21 < value && value <= -1e-6) || (1e-6 <= value && value < 1e21);
        }

        protected static boolean hasRegularToStringInInt32Range(double value) {
            return (Integer.MIN_VALUE - 1.0 < value && value <= -1) || (value == 0) || (1e-6 <= value && value < Integer.MAX_VALUE + 1.0);
        }

        @Specialization(guards = "hasRegularToString(thing)")
        protected double parseInt(double thing, Object radix0,
                        @Cached("create()") BranchProfile needsRadixConversion) {
            int radix = toInt32(radix0);
            if (radix == 0) {
                radix = 10;
            } else if (radix < 2 || radix > 36) {
                needsNaN.enter();
                return Double.NaN;
            }
            double truncated = JSRuntime.truncateDouble2(thing);
            if (radix == 10) {
                return truncated;
            } else {
                needsRadixConversion.enter();
                return convertToRadix(truncated, radix);
            }
        }

        @Specialization
        protected Object parseIntGeneric(Object thing, Object radix0,
                        @Cached("create()") JSToStringNode toStringNode,
                        @Cached("create()") JSTrimWhitespaceNode trimWhitespaceNode,
                        @Cached("create()") BranchProfile needsRadix16,
                        @Cached("create()") BranchProfile needsDontFitLong,
                        @Cached("create()") BranchProfile needsTrimming) {
            String inputString = trimWhitespaceNode.executeString(toStringNode.executeString(thing));
            int radix = toInt32(radix0);
            if (inputString.length() <= 0) {
                needsNaN.enter();
                return Double.NaN;
            }

            if (radix == 16 || radix == 0) {
                needsRadix16.enter();
                if (hasHexStart(inputString)) {
                    needsTrimming.enter();
                    if (inputString.charAt(0) == '0') {
                        inputString = Boundaries.substring(inputString, 2);
                    } else {
                        String sign = Boundaries.substring(inputString, 0, 1);
                        String number = Boundaries.substring(inputString, 3);
                        inputString = JSRuntime.stringConcat(sign, number);
                    }
                    radix = 16; // could be 0
                } else if (radix == 0) {
                    radix = 10;
                }
            } else if (radix < 2 || radix > 36) {
                needsNaN.enter();
                return Double.NaN;
            }

            int len = validStringLength(inputString, radix);
            if (len <= 0) {
                needsNaN.enter();
                return Double.NaN;
            }
            if ((radix <= 10 && len >= 18) || (10 < radix && radix <= 16 && len >= 15) || (radix > 16 && len >= 12)) {
                needsDontFitLong.enter();
                if (radix == 10) {
                    // parseRawDontFitLong() can produce an incorrect result
                    // due to subtle rounding errors (for radix 10) but the spec.
                    // requires exact processing for this radix
                    return parseDouble(Boundaries.substring(inputString, 0, len));
                } else {
                    return JSRuntime.parseRawDontFitLong(inputString, radix, len);
                }
            }
            return JSRuntime.parseRawFitsLong(inputString, radix, len);
        }

        @TruffleBoundary
        private static double parseDouble(String s) {
            return Double.parseDouble(s);
        }

        private static Object convertToRadix(int thing, int radix) {
            assert radix >= 2 && radix <= 36;
            boolean negative = thing < 0;
            long value = thing;
            if (negative) {
                value = -value;
            }
            long result = 0;
            long radixVal = 1;
            while (value != 0) {
                long digit = value % 10;
                if (digit >= radix) {
                    return Double.NaN;
                }
                result += digit * radixVal;
                value /= 10;
                radixVal *= radix;
            }
            if (negative) {
                result = -result;
            }
            return JSRuntime.longToIntOrDouble(result);
        }

        private static double convertToRadix(double thing, int radix) {
            assert (radix >= 2 && radix <= 36);
            boolean negative = thing < 0;
            double value = negative ? -thing : thing;
            double result = 0;
            double radixVal = 1;
            while (value != 0) {
                double digit = (value % 10);
                if (digit >= radix) {
                    return Double.NaN;
                }
                result += digit * radixVal;
                value -= digit;
                value /= 10;
                radixVal *= radix;
            }
            return negative ? -result : result;
        }

        private static boolean hasHexStart(String inputString) {
            int length = inputString.length();
            if (length >= 2 && inputString.charAt(0) == '0') {
                char c1 = inputString.charAt(1);
                return (c1 == 'x' || c1 == 'X');
            } else if (length >= 3 && inputString.charAt(1) == '0') {
                char c0 = inputString.charAt(0);
                if (c0 == '-' || c0 == '+') {
                    char c2 = inputString.charAt(2);
                    return (c2 == 'x' || c2 == 'X');
                }
            }
            return false;
        }

        @TruffleBoundary
        private static int validStringLength(String thing, int radix) {
            boolean hasSign = false;
            int pos = 0;
            if (!thing.isEmpty()) {
                char c = thing.charAt(0);
                if (c == '+' || c == '-') {
                    hasSign = true;
                    pos++;
                }
            }
            while (pos < thing.length()) {
                char c = thing.charAt(pos);
                if (JSRuntime.valueInRadix(c, radix) == -1) {
                    break;
                }
                pos++;
            }
            if (pos == 1 && hasSign) {
                pos = 0; // sign only
            }
            return pos;
        }
    }

    /**
     * Implementation of ECMAScript 5.1 15.1.3.3 encodeURI() and of ECMAScript 5.1 15.1.3.4
     * encodeURIComponent().
     *
     */
    public abstract static class JSGlobalEncodeURINode extends JSGlobalOperation {

        private final JSURLEncoder encoder;

        public JSGlobalEncodeURINode(JSContext context, JSBuiltin builtin, boolean isSpecial) {
            super(context, builtin);
            this.encoder = new JSURLEncoder(isSpecial);
        }

        @Specialization
        protected String encodeURI(Object value) {
            return encoder.encode(toString1(value));
        }
    }

    /**
     * Implementation of ECMAScript 5.1 15.1.3.1 decodeURI() and of ECMAScript 5.1 15.1.3.2
     * decodeURIComponent().
     *
     */
    public abstract static class JSGlobalDecodeURINode extends JSGlobalOperation {

        private final JSURLDecoder decoder;

        public JSGlobalDecodeURINode(JSContext context, JSBuiltin builtin, boolean isSpecial) {
            super(context, builtin);
            this.decoder = new JSURLDecoder(isSpecial);
        }

        @Specialization
        protected String decodeURI(Object value) {
            return decoder.decode(toString1(value));
        }
    }

    /**
     * This node is used only for indirect calls to eval. Direct calls are handled by
     * {@link EvalNode}.
     */
    public abstract static class JSGlobalIndirectEvalNode extends JSBuiltinNode {

        public JSGlobalIndirectEvalNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization
        protected Object indirectEvalString(String source) {
            JSRealm realm = getContext().getRealm();
            return indirectEvalImpl(realm, source);
        }

        @Specialization(guards = "isForeignObject(source)", limit = "3")
        protected Object indirectEvalForeignObject(TruffleObject source,
                        @CachedLibrary("source") InteropLibrary interop) {
            if (interop.isString(source)) {
                try {
                    return indirectEvalString(interop.asString(source));
                } catch (UnsupportedMessageException ex) {
                    throw Errors.createTypeErrorInteropException(source, ex, "asString", this);
                }
            } else {
                return source;
            }
        }

        @TruffleBoundary(transferToInterpreterOnException = false)
        private Object indirectEvalImpl(JSRealm realm, String source) {
            String sourceName = null;
            if (isCallerSensitive()) {
                sourceName = EvalNode.findAndFormatEvalOrigin(realm.getCallNode());
            }
            if (sourceName == null) {
                sourceName = Evaluator.EVAL_SOURCE_NAME;
            }
            return getContext().getEvaluator().evaluate(realm, this, Source.newBuilder(JavaScriptLanguage.ID, source, sourceName).build());
        }

        @Specialization
        protected int indirectEvalInt(int source) {
            return source;
        }

        @Specialization
        protected LargeInteger indirectEvalLargeInteger(LargeInteger source) {
            return source;
        }

        @Specialization
        protected long indirectEvalLong(long source) {
            return source;
        }

        @Specialization
        protected double indirectEvalDouble(double source) {
            return source;
        }

        @Specialization
        protected boolean indirectEvalBoolean(boolean source) {
            return source;
        }

        @Specialization
        protected Symbol indirectEvalSymbol(Symbol source) {
            return source;
        }

        @Specialization
        protected BigInt indirectEvalBigInt(BigInt source) {
            return source;
        }

        @Specialization(guards = "isJSType(object)")
        public DynamicObject indirectEvalJSType(DynamicObject object) {
            return object;
        }

        @Override
        public boolean isCallerSensitive() {
            return getContext().isOptionV8CompatibilityMode();
        }
    }

    /**
     * Implementation of ECMAScript 5.1 B.2.1 escape() method and of ECMAScript 5.1 B.2.2 unescape()
     * method.
     *
     */
    public abstract static class JSGlobalUnEscapeNode extends JSGlobalOperation {
        private final boolean unescape;

        public JSGlobalUnEscapeNode(JSContext context, JSBuiltin builtin, boolean unescape) {
            super(context, builtin);
            this.unescape = unescape;
        }

        @Specialization
        protected String escape(Object value) {
            String s = toString1(value);
            return unescape ? StringEscape.unescape(s) : StringEscape.escape(s);
        }
    }

    /**
     * Non-standard print()/printErr() method to write to the console.
     */
    public abstract static class JSGlobalPrintNode extends JSGlobalOperation {

        private final ConditionProfile argumentsCount = ConditionProfile.createBinaryProfile();
        private final BranchProfile consoleIndentation = BranchProfile.create();
        private final boolean useErr;

        public JSGlobalPrintNode(JSContext context, JSBuiltin builtin, boolean useErr) {
            super(context, builtin);
            this.useErr = useErr;
        }

        public abstract Object executeObjectArray(Object[] args);

        @Specialization
        protected Object print(Object[] arguments) {
            // without a StringBuilder, synchronization fails testnashorn JDK-8041998.js
            StringBuilder builder = new StringBuilder();
            JSConsoleUtil consoleUtil = getContext().getRealm().getConsoleUtil();
            if (consoleUtil.getConsoleIndentation() > 0) {
                consoleIndentation.enter();
                Boundaries.builderAppend(builder, consoleUtil.getConsoleIndentationString());
            }
            if (argumentsCount.profile(arguments.length == 1)) {
                Boundaries.builderAppend(builder, toString1(arguments[0]));
            } else {
                for (int i = 0; i < arguments.length; i++) {
                    if (i != 0) {
                        Boundaries.builderAppend(builder, ' ');
                    }
                    Boundaries.builderAppend(builder, toString1(arguments[i]));
                }
            }
            return printIntl(builder);
        }

        @TruffleBoundary
        private Object printIntl(StringBuilder builder) {
            builder.append(JSRuntime.LINE_SEPARATOR);
            JSRealm realm = getContext().getRealm();
            PrintWriter writer = useErr ? realm.getErrorWriter() : realm.getOutputWriter();
            writer.print(builder.toString());
            writer.flush();
            return Undefined.instance;
        }
    }

    @ImportStatic(value = JSInteropUtil.class)
    public abstract static class JSGlobalLoadNode extends JSLoadOperation {

        public JSGlobalLoadNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization
        protected Object loadString(String path, @SuppressWarnings("unused") Object[] args) {
            JSRealm realm = getContext().getRealm();
            return loadString(path, realm);
        }

        private Object loadString(String path, JSRealm realm) {
            Source source = sourceFromPath(path, realm);
            return runImpl(realm, source);
        }

        @Specialization(guards = "isForeignObject(scriptObj)")
        protected Object loadTruffleObject(TruffleObject scriptObj, @SuppressWarnings("unused") Object[] args,
                        @CachedLibrary(limit = "3") InteropLibrary interop) {
            JSRealm realm = getContext().getRealm();
            TruffleLanguage.Env env = realm.getEnv();
            if (env.isHostObject(scriptObj)) {
                Object hostObject = env.asHostObject(scriptObj);
                if (hostObject instanceof File) {
                    return loadFile(realm, (File) hostObject);
                } else if (getContext().isOptionNashornCompatibilityMode() && hostObject instanceof URL) {
                    return loadURL(realm, (URL) hostObject);
                }
            }
            Object unboxed = JSInteropUtil.toPrimitiveOrDefault(scriptObj, Null.instance, interop, this);
            if (unboxed == Null.instance) {
                throw cannotLoadScript(scriptObj);
            }
            String stringPath = toString1(unboxed);
            return loadString(stringPath, realm);
        }

        @Specialization(guards = "isJSObject(scriptObj)")
        protected Object loadScriptObj(DynamicObject scriptObj, Object[] args,
                        @Cached("create()") JSToStringNode sourceToStringNode) {
            JSRealm realm = getContext().getRealm();
            if (JSObject.hasProperty(scriptObj, EVAL_OBJ_FILE_NAME) && JSObject.hasProperty(scriptObj, EVAL_OBJ_SOURCE)) {
                Object scriptNameObj = JSObject.get(scriptObj, EVAL_OBJ_FILE_NAME);
                Object sourceObj = JSObject.get(scriptObj, EVAL_OBJ_SOURCE);
                return evalImpl(realm, toString1(scriptNameObj), sourceToStringNode.executeString(sourceObj), args);
            } else {
                throw cannotLoadScript(scriptObj);
            }
        }

        @Specialization(guards = {"!isString(fileName)", "!isForeignObject(fileName)", "!isJSObject(fileName)"})
        protected Object loadConvertToString(Object fileName, Object[] args) {
            return loadString(toString1(fileName), args);
        }

        protected Object loadFile(JSRealm realm, File file) {
            return runImpl(realm, sourceFromFileName(fileGetPath(file), realm));
        }

        protected Object loadURL(JSRealm realm, URL url) {
            assert getContext().isOptionNashornCompatibilityMode();
            return runImpl(realm, sourceFromURL(url));
        }

        @TruffleBoundary(transferToInterpreterOnException = false)
        protected Object evalImpl(JSRealm realm, String fileName, String source, @SuppressWarnings("unused") Object[] args) {
            return loadStringImpl(getContext(), fileName, source).run(realm);
        }
    }

    /**
     * Implementation of non-standard method loadWithNewGlobal() as defined by Nashorn.
     *
     */
    public abstract static class JSGlobalLoadWithNewGlobalNode extends JSGlobalLoadNode {

        public JSGlobalLoadWithNewGlobalNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Override
        @TruffleBoundary(transferToInterpreterOnException = false)
        protected Object evalImpl(JSRealm realm, String fileName, String source, Object[] args) {
            JSRealm newRealm = realm.createChildRealm();
            DynamicObject argObj = JSArgumentsObject.createStrict(getContext(), newRealm, args);
            JSRuntime.createDataProperty(newRealm.getGlobalObject(), JSFunction.ARGUMENTS, argObj);
            return loadStringImpl(getContext(), fileName, source).run(newRealm);
        }
    }

    /**
     * Non-standard global exit() and quit() functions to provide compatibility with Nashorn (both)
     * and V8 (only quit).
     */
    public abstract static class JSGlobalExitNode extends JSBuiltinNode {

        public JSGlobalExitNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization
        protected Object exit(Object[] arg,
                        @Cached("create()") JSToNumberNode toNumberNode) {
            int exitCode = arg.length == 0 ? 0 : (int) JSRuntime.toInteger(toNumberNode.executeNumber(arg[0]));
            throw new ExitException(exitCode, this);
        }
    }

    /**
     * Non-standard readline() for V8 compatibility, and readLine(prompt) for Nashorn compatibility
     * (only available in nashorn-compat mode with scripting enabled).
     *
     * The prompt argument is only accepted and printed by Nashorn's variant.
     */
    public abstract static class JSGlobalReadLineNode extends JSGlobalOperation {

        public JSGlobalReadLineNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization
        protected String readLine(Object prompt) {
            String promptString = null;
            if (prompt != Undefined.instance) {
                promptString = toString1(prompt);
            }
            return doReadLine(promptString);
        }

        @TruffleBoundary
        private String doReadLine(String promptString) {
            if (promptString != null) {
                getContext().getRealm().getOutputWriter().print(promptString);
            }
            try {
                final BufferedReader inReader = new BufferedReader(new InputStreamReader(getContext().getRealm().getEnv().in()));
                return inReader.readLine();
            } catch (Exception ex) {
                throw Errors.createError(ex.getMessage());
            }
        }

    }

    static TruffleFile getFileFromArgument(Object arg, TruffleLanguage.Env env) {
        CompilerAsserts.neverPartOfCompilation();
        try {
            String path;
            if (JSRuntime.isString(arg)) {
                path = arg.toString();
            } else if (env.isHostObject(arg) && env.asHostObject(arg) instanceof File) {
                path = ((File) env.asHostObject(arg)).getPath();
            } else {
                path = JSRuntime.toString(arg);
            }

            TruffleFile file = resolveRelativeFilePath(path, env);
            if (!file.isRegularFile()) {
                throw Errors.createNotAFileError(path);
            }
            return file;
        } catch (SecurityException e) {
            throw Errors.createErrorFromException(e);
        }
    }

    /**
     * Non-standard read() and readFully() to provide compatibility with V8 and Nashorn,
     * respectively.
     */
    public abstract static class JSGlobalReadFullyNode extends JSBuiltinNode {
        private static final int BUFFER_SIZE = 2048;

        public JSGlobalReadFullyNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization
        @TruffleBoundary(transferToInterpreterOnException = false)
        protected String read(Object fileParam) {
            TruffleFile file = getFileFromArgument(fileParam, getContext().getRealm().getEnv());

            try {
                return readImpl(file.newBufferedReader());
            } catch (Exception ex) {
                throw Errors.createErrorFromException(ex);
            }
        }

        private static String readImpl(BufferedReader reader) throws IOException {
            final StringBuilder sb = new StringBuilder();
            final char[] arr = new char[BUFFER_SIZE];
            try {
                int numChars;
                while ((numChars = reader.read(arr, 0, arr.length)) > 0) {
                    sb.append(arr, 0, numChars);
                }
            } finally {
                reader.close();
            }
            return sb.toString();
        }
    }

    /**
     * Non-standard readbuffer() to provide compatibility with V8.
     */
    public abstract static class JSGlobalReadBufferNode extends JSBuiltinNode {

        public JSGlobalReadBufferNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization
        @TruffleBoundary(transferToInterpreterOnException = false)
        protected final DynamicObject readbuffer(Object fileParam) {
            TruffleFile file = getFileFromArgument(fileParam, getContext().getRealm().getEnv());

            try {
                final byte[] bytes = file.readAllBytes();

                final DynamicObject arrayBuffer;
                if (getContext().isOptionDirectByteBuffer()) {
                    ByteBuffer buffer = ByteBuffer.allocateDirect(bytes.length);
                    buffer.put(bytes);
                    asBaseBuffer(buffer).rewind();
                    arrayBuffer = JSArrayBuffer.createDirectArrayBuffer(getContext(), buffer);
                } else {
                    arrayBuffer = JSArrayBuffer.createArrayBuffer(getContext(), bytes);
                }
                return arrayBuffer;
            } catch (Exception ex) {
                throw Errors.createErrorFromException(ex);
            }
        }
    }

    /**
     * Non-standard import helper function for support of global scope bindings in
     * GraalJSScriptEngine.
     */
    abstract static class JSGlobalImportScriptEngineGlobalBindingsNode extends JSBuiltinNode {

        JSGlobalImportScriptEngineGlobalBindingsNode(JSContext context, JSBuiltin builtin) {
            super(context, builtin);
        }

        @Specialization
        final Object importGlobalContext(TruffleObject globalContextBindings) {
            doImport(globalContextBindings);
            return Undefined.instance;
        }

        @TruffleBoundary
        private void doImport(TruffleObject globalContextBindings) {
            DynamicObject globalObject = getContext().getRealm().getGlobalObject();
            Bindings bindings = (Bindings) getContext().getRealm().getEnv().asHostObject(globalContextBindings);
            for (Map.Entry<String, Object> entry : bindings.entrySet()) {
                String key = entry.getKey();
                if (!globalObject.getShape().hasProperty(key) && !JSObject.getPrototype(globalObject).getShape().hasProperty(key)) {
                    JSObjectUtil.defineProxyProperty(globalObject, key, new ScriptEngineGlobalScopeBindingsPropertyProxy(getContext(), bindings, key), JSAttributes.getDefault());
                }
            }
        }

        private static class ScriptEngineGlobalScopeBindingsPropertyProxy implements PropertyProxy {

            private final JSContext context;
            private final Bindings globalContextBindings;
            private final String key;

            ScriptEngineGlobalScopeBindingsPropertyProxy(JSContext context, Bindings globalContextBindings, String key) {
                this.context = context;
                this.globalContextBindings = globalContextBindings;
                this.key = key;
            }

            @Override
            @TruffleBoundary
            public Object get(DynamicObject store) {
                Object value = globalContextBindings.get(key);
                if (value == null) {
                    return Undefined.instance;
                }
                return JSRuntime.importValue(context.getRealm().getEnv().asGuestValue(value));
            }

            @Override
            public boolean set(DynamicObject store, Object value) {
                JSObjectUtil.defineDataProperty(store, key, value, JSAttributes.getDefault());
                return true;
            }
        }
    }
}
