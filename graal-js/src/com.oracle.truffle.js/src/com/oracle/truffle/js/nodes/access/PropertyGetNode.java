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
package com.oracle.truffle.js.nodes.access;

import static com.oracle.truffle.js.runtime.builtins.JSAbstractArray.arrayGetRegexResult;

import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.interop.ArityException;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.interop.UnknownIdentifierException;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.interop.UnsupportedTypeException;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.ExplodeLoop.LoopExplosionKind;
import com.oracle.truffle.api.nodes.NodeCost;
import com.oracle.truffle.api.nodes.NodeInfo;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import com.oracle.truffle.api.object.BooleanLocation;
import com.oracle.truffle.api.object.DoubleLocation;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.object.HiddenKey;
import com.oracle.truffle.api.object.IntLocation;
import com.oracle.truffle.api.object.LongLocation;
import com.oracle.truffle.api.object.ObjectLocation;
import com.oracle.truffle.api.object.Property;
import com.oracle.truffle.api.object.Shape;
import com.oracle.truffle.api.profiles.BranchProfile;
import com.oracle.truffle.api.profiles.ConditionProfile;
import com.oracle.truffle.api.profiles.ValueProfile;
import com.oracle.truffle.js.nodes.JSGuards;
import com.oracle.truffle.js.nodes.JSTypesGen;
import com.oracle.truffle.js.nodes.JavaScriptBaseNode;
import com.oracle.truffle.js.nodes.access.PropertyGetNodeFactory.GetPropertyFromJSObjectNodeGen;
import com.oracle.truffle.js.nodes.array.ArrayLengthNode.ArrayLengthReadNode;
import com.oracle.truffle.js.nodes.cast.JSToObjectNode;
import com.oracle.truffle.js.nodes.function.CreateMethodPropertyNode;
import com.oracle.truffle.js.nodes.function.JSFunctionCallNode;
import com.oracle.truffle.js.nodes.interop.ForeignObjectPrototypeNode;
import com.oracle.truffle.js.nodes.interop.JSForeignToJSTypeNode;
import com.oracle.truffle.js.nodes.interop.JSForeignToJSTypeNodeGen;
import com.oracle.truffle.js.runtime.Errors;
import com.oracle.truffle.js.runtime.JSArguments;
import com.oracle.truffle.js.runtime.JSContext;
import com.oracle.truffle.js.runtime.JSNoSuchMethodAdapter;
import com.oracle.truffle.js.runtime.JSRealm;
import com.oracle.truffle.js.runtime.JSRuntime;
import com.oracle.truffle.js.runtime.JSTruffleOptions;
import com.oracle.truffle.js.runtime.Symbol;
import com.oracle.truffle.js.runtime.builtins.JSAbstractArray;
import com.oracle.truffle.js.runtime.builtins.JSAdapter;
import com.oracle.truffle.js.runtime.builtins.JSArray;
import com.oracle.truffle.js.runtime.builtins.JSArrayBufferView;
import com.oracle.truffle.js.runtime.builtins.JSClass;
import com.oracle.truffle.js.runtime.builtins.JSFunction;
import com.oracle.truffle.js.runtime.builtins.JSFunctionData;
import com.oracle.truffle.js.runtime.builtins.JSModuleNamespace;
import com.oracle.truffle.js.runtime.builtins.JSProxy;
import com.oracle.truffle.js.runtime.builtins.JSRegExp;
import com.oracle.truffle.js.runtime.builtins.JSString;
import com.oracle.truffle.js.runtime.builtins.JSUserObject;
import com.oracle.truffle.js.runtime.java.JavaImporter;
import com.oracle.truffle.js.runtime.java.JavaPackage;
import com.oracle.truffle.js.runtime.objects.Accessor;
import com.oracle.truffle.js.runtime.objects.JSObject;
import com.oracle.truffle.js.runtime.objects.JSProperty;
import com.oracle.truffle.js.runtime.objects.JSShape;
import com.oracle.truffle.js.runtime.objects.Null;
import com.oracle.truffle.js.runtime.objects.Undefined;
import com.oracle.truffle.js.runtime.util.TRegexUtil;
import com.oracle.truffle.js.runtime.util.TRegexUtil.TRegexMaterializeResultNode;

/**
 * ES6 9.1.8 [[Get]] (P, Receiver).
 *
 * @see PropertyNode
 * @see GlobalPropertyNode
 */
public class PropertyGetNode extends PropertyCacheNode<PropertyGetNode.GetCacheNode> {
    private final boolean isGlobal;
    private final boolean getOwnProperty;
    @CompilationFinal private boolean isMethod;
    private boolean propertyAssumptionCheckEnabled = true;

    public static PropertyGetNode create(Object key, JSContext context) {
        return create(key, false, context);
    }

    public static PropertyGetNode create(Object key, boolean isGlobal, JSContext context) {
        final boolean getOwnProperty = false;
        return createImpl(key, isGlobal, context, getOwnProperty);
    }

    private static PropertyGetNode createImpl(Object key, boolean isGlobal, JSContext context, boolean getOwnProperty) {
        return new PropertyGetNode(key, context, isGlobal, getOwnProperty);
    }

    public static PropertyGetNode createGetOwn(Object key, JSContext context) {
        final boolean global = false;
        final boolean getOwnProperty = true;
        return createImpl(key, global, context, getOwnProperty);
    }

    public static PropertyGetNode createGetHidden(HiddenKey key, JSContext context) {
        return createGetOwn(key, context);
    }

    protected PropertyGetNode(Object key, JSContext context, boolean isGlobal, boolean getOwnProperty) {
        super(key, context);
        this.isGlobal = isGlobal;
        this.getOwnProperty = getOwnProperty;
    }

    public final Object getValue(Object obj) {
        return getValue(obj, obj);
    }

    public final int getValueInt(Object obj) throws UnexpectedResultException {
        return getValueInt(obj, obj);
    }

    public final double getValueDouble(Object obj) throws UnexpectedResultException {
        return getValueDouble(obj, obj);
    }

    public final boolean getValueBoolean(Object obj) throws UnexpectedResultException {
        return getValueBoolean(obj, obj);
    }

    public final long getValueLong(Object obj) throws UnexpectedResultException {
        return getValueLong(obj, obj);
    }

    public final Object getValueOrDefault(Object obj, Object defaultValue) {
        return getValueOrDefault(obj, obj, defaultValue);
    }

    @ExplodeLoop(kind = LoopExplosionKind.FULL_EXPLODE_UNTIL_RETURN)
    protected Object getValue(Object thisObj, Object receiver) {
        for (GetCacheNode c = cacheNode; c != null; c = c.next) {
            if (c.isGeneric()) {
                return c.getValue(thisObj, receiver, this, false);
            }
            if (!c.isValid()) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                break;
            }
            boolean guard = c.accepts(thisObj);
            if (guard) {
                return c.getValue(thisObj, receiver, this, guard);
            }
        }
        deoptimize();
        return specialize(thisObj).getValue(thisObj, receiver, this, false);
    }

    @ExplodeLoop(kind = LoopExplosionKind.FULL_EXPLODE_UNTIL_RETURN)
    protected int getValueInt(Object thisObj, Object receiver) throws UnexpectedResultException {
        for (GetCacheNode c = cacheNode; c != null; c = c.next) {
            if (c.isGeneric()) {
                return c.getValueInt(thisObj, receiver, this, false);
            }
            if (!c.isValid()) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                break;
            }
            boolean guard = c.accepts(thisObj);
            if (guard) {
                return c.getValueInt(thisObj, receiver, this, guard);
            }
        }
        deoptimize();
        return specialize(thisObj).getValueInt(thisObj, receiver, this, false);
    }

    @ExplodeLoop(kind = LoopExplosionKind.FULL_EXPLODE_UNTIL_RETURN)
    protected double getValueDouble(Object thisObj, Object receiver) throws UnexpectedResultException {
        for (GetCacheNode c = cacheNode; c != null; c = c.next) {
            if (c.isGeneric()) {
                return c.getValueDouble(thisObj, receiver, this, false);
            }
            if (!c.isValid()) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                break;
            }
            boolean guard = c.accepts(thisObj);
            if (guard) {
                return c.getValueDouble(thisObj, receiver, this, guard);
            }
        }
        deoptimize();
        return specialize(thisObj).getValueDouble(thisObj, receiver, this, false);
    }

    @ExplodeLoop(kind = LoopExplosionKind.FULL_EXPLODE_UNTIL_RETURN)
    protected boolean getValueBoolean(Object thisObj, Object receiver) throws UnexpectedResultException {
        for (GetCacheNode c = cacheNode; c != null; c = c.next) {
            if (c.isGeneric()) {
                return c.getValueBoolean(thisObj, receiver, this, false);
            }
            if (!c.isValid()) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                break;
            }
            boolean guard = c.accepts(thisObj);
            if (guard) {
                return c.getValueBoolean(thisObj, receiver, this, guard);
            }
        }
        deoptimize();
        return specialize(thisObj).getValueBoolean(thisObj, receiver, this, false);
    }

    @ExplodeLoop(kind = LoopExplosionKind.FULL_EXPLODE_UNTIL_RETURN)
    protected long getValueLong(Object thisObj, Object receiver) throws UnexpectedResultException {
        for (GetCacheNode c = cacheNode; c != null; c = c.next) {
            if (c.isGeneric()) {
                return c.getValueLong(thisObj, receiver, this, false);
            }
            if (!c.isValid()) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                break;
            }
            boolean guard = c.accepts(thisObj);
            if (guard) {
                return c.getValueLong(thisObj, receiver, this, guard);
            }
        }
        deoptimize();
        return specialize(thisObj).getValueLong(thisObj, receiver, this, false);
    }

    @ExplodeLoop(kind = LoopExplosionKind.FULL_EXPLODE_UNTIL_RETURN)
    protected Object getValueOrDefault(Object thisObj, Object receiver, Object defaultValue) {
        for (GetCacheNode c = cacheNode; c != null; c = c.next) {
            if (c.isGeneric()) {
                return c.getValueOrDefault(thisObj, receiver, defaultValue, this, false);
            }
            if (!c.isValid()) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                break;
            }
            boolean guard = c.accepts(thisObj);
            if (guard) {
                return c.getValueOrDefault(thisObj, receiver, defaultValue, this, guard);
            }
        }
        deoptimize();
        return specialize(thisObj).getValueOrDefault(thisObj, receiver, defaultValue, this, false);
    }

    public abstract static class GetCacheNode extends PropertyCacheNode.CacheNode<GetCacheNode> {
        protected GetCacheNode(ReceiverCheckNode receiverCheck) {
            super(receiverCheck);
        }

        protected abstract Object getValue(Object thisObj, Object receiver, PropertyGetNode root, boolean guard);

        @SuppressWarnings("unused")
        protected Object getValueOrDefault(Object thisObj, Object receiver, Object defaultValue, PropertyGetNode root, boolean guard) {
            return getValue(thisObj, receiver, root, guard);
        }

        protected int getValueInt(Object thisObj, Object receiver, PropertyGetNode root, boolean guard) throws UnexpectedResultException {
            return JSTypesGen.expectInteger(getValue(thisObj, receiver, root, guard));
        }

        protected double getValueDouble(Object thisObj, Object receiver, PropertyGetNode root, boolean guard) throws UnexpectedResultException {
            return JSTypesGen.expectDouble(getValue(thisObj, receiver, root, guard));
        }

        protected boolean getValueBoolean(Object thisObj, Object receiver, PropertyGetNode root, boolean guard) throws UnexpectedResultException {
            return JSTypesGen.expectBoolean(getValue(thisObj, receiver, root, guard));
        }

        protected long getValueLong(Object thisObj, Object receiver, PropertyGetNode root, boolean guard) throws UnexpectedResultException {
            return JSTypesGen.expectLong(getValue(thisObj, receiver, root, guard));
        }
    }

    public abstract static class LinkedPropertyGetNode extends GetCacheNode {
        protected LinkedPropertyGetNode(ReceiverCheckNode receiverCheck) {
            super(receiverCheck);
        }
    }

    public static final class ObjectPropertyGetNode extends LinkedPropertyGetNode {

        private final Property property;

        public ObjectPropertyGetNode(Property property, ReceiverCheckNode receiverCheck) {
            super(receiverCheck);
            assert JSProperty.isData(property);
            this.property = property;
        }

        @Override
        protected Object getValue(Object thisObj, Object receiver, PropertyGetNode root, boolean guard) {
            return JSProperty.getValue(property, receiverCheck.getStore(thisObj), receiver, guard);
        }
    }

    protected abstract static class AbstractFinalDataPropertyGetNode extends LinkedPropertyGetNode {
        private final Assumption finalAssumption;

        protected AbstractFinalDataPropertyGetNode(Property property, AbstractShapeCheckNode shapeCheck) {
            super(shapeCheck);
            this.finalAssumption = property.getLocation().isFinal() ? null : property.getLocation().getFinalAssumption();
        }

        @Override
        protected boolean isValid() {
            return super.isValid() && (finalAssumption == null || finalAssumption.isValid());
        }

        protected final boolean assertFinalValue(Object finalValue, Object thisObj, PropertyGetNode root) {
            if (!JSTruffleOptions.AssertFinalPropertySpecialization) {
                return true;
            }
            int depth = ((AbstractShapeCheckNode) receiverCheck).getDepth();
            DynamicObject store = (DynamicObject) thisObj;
            for (int i = 0; i < depth; i++) {
                store = JSObject.getPrototype(store);
            }
            return finalValue.equals(store.get(root.getKey()));
        }
    }

    public static final class FinalObjectPropertyGetNode extends AbstractFinalDataPropertyGetNode {

        private final Object finalValue;

        public FinalObjectPropertyGetNode(Property property, AbstractShapeCheckNode shapeCheck, Object value) {
            super(property, shapeCheck);
            assert JSProperty.isData(property);
            this.finalValue = value;
        }

        @Override
        protected Object getValue(Object thisObj, Object receiver, PropertyGetNode root, boolean guard) {
            assert assertFinalValue(finalValue, thisObj, root);
            return finalValue;
        }
    }

    public static final class IntPropertyGetNode extends LinkedPropertyGetNode {

        private final IntLocation location;

        public IntPropertyGetNode(Property property, ReceiverCheckNode receiverCheck) {
            super(receiverCheck);
            assert JSProperty.isData(property);
            this.location = (IntLocation) property.getLocation();
        }

        @Override
        protected Object getValue(Object thisObj, Object receiver, PropertyGetNode root, boolean guard) {
            return location.getInt(receiverCheck.getStore(thisObj), guard);
        }

        @Override
        protected int getValueInt(Object thisObj, Object receiver, PropertyGetNode root, boolean guard) {
            return location.getInt(receiverCheck.getStore(thisObj), guard);
        }

        @Override
        protected double getValueDouble(Object thisObj, Object receiver, PropertyGetNode root, boolean guard) {
            return location.getInt(receiverCheck.getStore(thisObj), guard);
        }
    }

    public static final class FinalIntPropertyGetNode extends AbstractFinalDataPropertyGetNode {

        private final int finalValue;

        public FinalIntPropertyGetNode(Property property, AbstractShapeCheckNode shapeCheck, int value) {
            super(property, shapeCheck);
            assert JSProperty.isData(property);
            this.finalValue = value;
        }

        @Override
        protected Object getValue(Object thisObj, Object receiver, PropertyGetNode root, boolean guard) {
            return getValueInt(thisObj, receiver, root, guard);
        }

        @Override
        protected int getValueInt(Object thisObj, Object receiver, PropertyGetNode root, boolean guard) {
            assert assertFinalValue(finalValue, thisObj, root);
            return finalValue;
        }

        @Override
        protected double getValueDouble(Object thisObj, Object receiver, PropertyGetNode root, boolean guard) {
            return getValueInt(thisObj, receiver, root, guard);
        }
    }

    public static final class DoublePropertyGetNode extends LinkedPropertyGetNode {

        private final DoubleLocation location;

        public DoublePropertyGetNode(Property property, ReceiverCheckNode receiverCheck) {
            super(receiverCheck);
            assert JSProperty.isData(property);
            this.location = (DoubleLocation) property.getLocation();
        }

        @Override
        protected Object getValue(Object thisObj, Object receiver, PropertyGetNode root, boolean guard) {
            return location.getDouble(receiverCheck.getStore(thisObj), guard);
        }

        @Override
        protected double getValueDouble(Object thisObj, Object receiver, PropertyGetNode root, boolean guard) {
            return location.getDouble(receiverCheck.getStore(thisObj), guard);
        }
    }

    public static final class FinalDoublePropertyGetNode extends AbstractFinalDataPropertyGetNode {

        private final double finalValue;

        public FinalDoublePropertyGetNode(Property property, AbstractShapeCheckNode shapeCheck, double value) {
            super(property, shapeCheck);
            assert JSProperty.isData(property);
            this.finalValue = value;
        }

        @Override
        protected Object getValue(Object thisObj, Object receiver, PropertyGetNode root, boolean guard) {
            return getValueDouble(thisObj, receiver, root, guard);
        }

        @Override
        protected double getValueDouble(Object thisObj, Object receiver, PropertyGetNode root, boolean guard) {
            assert assertFinalValue(finalValue, thisObj, root);
            return finalValue;
        }
    }

    public static final class BooleanPropertyGetNode extends LinkedPropertyGetNode {

        private final BooleanLocation location;

        public BooleanPropertyGetNode(Property property, ReceiverCheckNode receiverCheck) {
            super(receiverCheck);
            assert JSProperty.isData(property);
            this.location = (BooleanLocation) property.getLocation();
        }

        @Override
        protected Object getValue(Object thisObj, Object receiver, PropertyGetNode root, boolean guard) {
            return getValueBoolean(thisObj, receiver, root, guard);
        }

        @Override
        protected boolean getValueBoolean(Object thisObj, Object receiver, PropertyGetNode root, boolean guard) {
            return location.getBoolean(receiverCheck.getStore(thisObj), guard);
        }
    }

    public static final class FinalBooleanPropertyGetNode extends AbstractFinalDataPropertyGetNode {

        private final boolean finalValue;

        public FinalBooleanPropertyGetNode(Property property, AbstractShapeCheckNode shapeCheck, boolean value) {
            super(property, shapeCheck);
            assert JSProperty.isData(property);
            this.finalValue = value;
        }

        @Override
        protected Object getValue(Object thisObj, Object receiver, PropertyGetNode root, boolean guard) {
            return getValueBoolean(thisObj, receiver, root, guard);
        }

        @Override
        protected boolean getValueBoolean(Object thisObj, Object receiver, PropertyGetNode root, boolean guard) {
            assert assertFinalValue(finalValue, thisObj, root);
            return finalValue;
        }
    }

    public static final class LongPropertyGetNode extends LinkedPropertyGetNode {

        private final LongLocation location;

        public LongPropertyGetNode(Property property, ReceiverCheckNode receiverCheck) {
            super(receiverCheck);
            assert JSProperty.isData(property);
            this.location = (LongLocation) property.getLocation();
        }

        @Override
        protected Object getValue(Object thisObj, Object receiver, PropertyGetNode root, boolean guard) {
            return location.getLong(receiverCheck.getStore(thisObj), guard);
        }

        @Override
        protected long getValueLong(Object thisObj, Object receiver, PropertyGetNode root, boolean guard) {
            return location.getLong(receiverCheck.getStore(thisObj), guard);
        }
    }

    public static final class FinalLongPropertyGetNode extends AbstractFinalDataPropertyGetNode {

        private final long finalValue;

        public FinalLongPropertyGetNode(Property property, AbstractShapeCheckNode shapeCheck, long value) {
            super(property, shapeCheck);
            assert JSProperty.isData(property);
            this.finalValue = value;
        }

        @Override
        protected Object getValue(Object thisObj, Object receiver, PropertyGetNode root, boolean guard) {
            return getValueLong(thisObj, receiver, root, guard);
        }

        @Override
        protected long getValueLong(Object thisObj, Object receiver, PropertyGetNode root, boolean guard) {
            assert assertFinalValue(finalValue, thisObj, root);
            return finalValue;
        }
    }

    public static final class AccessorPropertyGetNode extends LinkedPropertyGetNode {
        private final Property property;
        @Child private JSFunctionCallNode callNode;
        private final BranchProfile undefinedGetterBranch = BranchProfile.create();

        public AccessorPropertyGetNode(Property property, ReceiverCheckNode receiverCheck) {
            super(receiverCheck);
            assert JSProperty.isAccessor(property);
            this.property = property;
            this.callNode = JSFunctionCallNode.createCall();
        }

        @Override
        protected Object getValue(Object thisObj, Object receiver, PropertyGetNode root, boolean guard) {
            DynamicObject store = receiverCheck.getStore(thisObj);
            Accessor accessor = (Accessor) property.get(store, guard);

            DynamicObject getter = accessor.getGetter();
            if (getter != Undefined.instance) {
                return callNode.executeCall(JSArguments.createZeroArg(receiver, getter));
            } else {
                undefinedGetterBranch.enter();
                return Undefined.instance;
            }
        }
    }

    public static final class FinalAccessorPropertyGetNode extends LinkedPropertyGetNode {

        @Child private JSFunctionCallNode callNode;
        private final BranchProfile undefinedGetterBranch = BranchProfile.create();
        private final Accessor finalAccessor;
        private final Assumption finalAssumption;

        public FinalAccessorPropertyGetNode(Property property, ReceiverCheckNode receiverCheck, Accessor finalAccessor) {
            super(receiverCheck);
            assert JSProperty.isAccessor(property);
            this.callNode = JSFunctionCallNode.createCall();
            this.finalAccessor = finalAccessor;
            this.finalAssumption = property.getLocation().isFinal() ? null : property.getLocation().getFinalAssumption();
        }

        @Override
        protected Object getValue(Object thisObj, Object receiver, PropertyGetNode root, boolean guard) {
            DynamicObject getter = finalAccessor.getGetter();
            if (getter != Undefined.instance) {
                return callNode.executeCall(JSArguments.createZeroArg(receiver, getter));
            } else {
                undefinedGetterBranch.enter();
                return Undefined.instance;
            }
        }

        @Override
        protected boolean isValid() {
            return super.isValid() && (finalAssumption == null || finalAssumption.isValid());
        }
    }

    /**
     * For use when a property is undefined. Returns undefined.
     */
    public static final class UndefinedPropertyGetNode extends LinkedPropertyGetNode {

        public UndefinedPropertyGetNode(ReceiverCheckNode receiverCheck) {
            super(receiverCheck);
        }

        @Override
        protected Object getValue(Object thisObj, Object receiver, PropertyGetNode root, boolean guard) {
            return Undefined.instance;
        }

        @Override
        protected Object getValueOrDefault(Object thisObj, Object receiver, Object defaultValue, PropertyGetNode root, boolean guard) {
            return defaultValue;
        }
    }

    /**
     * For use when a global property is undefined. Throws ReferenceError.
     */
    public static final class UndefinedPropertyErrorNode extends LinkedPropertyGetNode {

        public UndefinedPropertyErrorNode(ReceiverCheckNode receiverCheck) {
            super(receiverCheck);
        }

        @Override
        protected Object getValue(Object thisObj, Object receiver, PropertyGetNode root, boolean guard) {
            throw Errors.createReferenceErrorNotDefined(root.getKey(), this);
        }
    }

    public static final class ArrayBufferViewNonIntegerIndexGetNode extends LinkedPropertyGetNode {

        public ArrayBufferViewNonIntegerIndexGetNode(ReceiverCheckNode receiverCheck) {
            super(receiverCheck);
        }

        @Override
        protected Object getValue(Object thisObj, Object receiver, PropertyGetNode root, boolean guard) {
            if (JSArrayBufferView.hasDetachedBuffer((DynamicObject) thisObj)) {
                throw Errors.createTypeErrorDetachedBuffer();
            } else {
                return Undefined.instance;
            }
        }

    }

    /**
     * For use when a property is undefined and __noSuchProperty__/__noSuchMethod__ had been set.
     */
    public static final class CheckNoSuchPropertyNode extends LinkedPropertyGetNode {
        private final JSContext context;
        @Child private PropertyGetNode getNoSuchPropertyNode;
        @Child private PropertyGetNode getNoSuchMethodNode;
        @Child private JSHasPropertyNode hasPropertyNode;
        @Child private JSFunctionCallNode callNoSuchNode;

        public CheckNoSuchPropertyNode(Object key, ReceiverCheckNode receiverCheck, JSContext context) {
            super(receiverCheck);
            this.context = context;
            assert !(key instanceof Symbol);
        }

        @Override
        protected Object getValue(Object thisObj, Object receiver, PropertyGetNode root, boolean guard) {
            if (JSRuntime.isObject(thisObj) && !JSAdapter.isJSAdapter(thisObj) && !JSProxy.isProxy(thisObj)) {
                if (!context.getNoSuchMethodUnusedAssumption().isValid() && root.isMethod() && getHasProperty().executeBoolean(thisObj, JSObject.NO_SUCH_METHOD_NAME)) {
                    Object function = getNoSuchMethod().getValue(thisObj);
                    if (function != Undefined.instance) {
                        if (JSFunction.isJSFunction(function)) {
                            return callNoSuchHandler((DynamicObject) thisObj, (DynamicObject) function, root, false);
                        } else {
                            return getFallback(root);
                        }
                    }
                }
                if (!context.getNoSuchPropertyUnusedAssumption().isValid()) {
                    Object function = getNoSuchProperty().getValue(thisObj);
                    if (JSFunction.isJSFunction(function)) {
                        return callNoSuchHandler((DynamicObject) thisObj, (DynamicObject) function, root, true);
                    }
                }
            }
            return getFallback(root);
        }

        private Object callNoSuchHandler(DynamicObject thisObj, DynamicObject function, PropertyGetNode root, boolean noSuchProperty) {
            // if accessing a global variable, pass undefined as `this` instead of global object.
            // only matters if callee is strict. cf. Nashorn ScriptObject.noSuch{Property,Method}.
            Object thisObject = root.isGlobal() ? Undefined.instance : thisObj;
            if (noSuchProperty) {
                return getCallNoSuch().executeCall(JSArguments.createOneArg(thisObject, function, root.getKey()));
            } else {
                return new JSNoSuchMethodAdapter(function, root.getKey(), thisObject);
            }
        }

        private Object getFallback(PropertyGetNode root) {
            if (root.isGlobal()) {
                throw Errors.createReferenceErrorNotDefined(root.getKey(), this);
            } else {
                return Undefined.instance;
            }
        }

        public PropertyGetNode getNoSuchProperty() {
            if (getNoSuchPropertyNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                getNoSuchPropertyNode = insert(PropertyGetNode.create(JSObject.NO_SUCH_PROPERTY_NAME, context));
            }
            return getNoSuchPropertyNode;
        }

        public PropertyGetNode getNoSuchMethod() {
            if (getNoSuchMethodNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                getNoSuchMethodNode = insert(PropertyGetNode.create(JSObject.NO_SUCH_METHOD_NAME, context));
            }
            return getNoSuchMethodNode;
        }

        public JSHasPropertyNode getHasProperty() {
            if (hasPropertyNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                hasPropertyNode = insert(JSHasPropertyNode.create());
            }
            return hasPropertyNode;
        }

        public JSFunctionCallNode getCallNoSuch() {
            if (callNoSuchNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                callNoSuchNode = insert(JSFunctionCallNode.createCall());
            }
            return callNoSuchNode;
        }
    }

    /**
     * If object is undefined or null, throw TypeError.
     */
    public static final class TypeErrorPropertyGetNode extends LinkedPropertyGetNode {
        public TypeErrorPropertyGetNode(ReceiverCheckNode receiverCheck) {
            super(receiverCheck);
        }

        @Override
        protected Object getValue(Object thisObj, Object receiver, PropertyGetNode root, boolean guard) {
            assert (thisObj == Undefined.instance || thisObj == Null.instance || thisObj == null) : thisObj;
            throw Errors.createTypeErrorCannotGetProperty(root.getKey(), thisObj, root.isMethod(), this);
        }
    }

    public static final class JavaPackagePropertyGetNode extends LinkedPropertyGetNode {
        public JavaPackagePropertyGetNode(ReceiverCheckNode receiverCheck) {
            super(receiverCheck);
        }

        @Override
        protected Object getValue(Object thisObj, Object receiver, PropertyGetNode root, boolean guard) {
            Object key = root.getKey();
            if (key instanceof String) {
                return JavaPackage.getJavaClassOrConstructorOrSubPackage(root.getContext(), (DynamicObject) thisObj, (String) key);
            } else {
                return Undefined.instance;
            }
        }
    }

    public static class JavaStringMethodGetNode extends LinkedPropertyGetNode {
        @Child private InteropLibrary interop;

        public JavaStringMethodGetNode(ReceiverCheckNode receiverCheck) {
            super(receiverCheck);
            this.interop = InteropLibrary.getFactory().createDispatched(3);
        }

        @Override
        protected Object getValue(Object thisObj, Object receiver, PropertyGetNode root, boolean guard) {
            String thisStr = (String) thisObj;
            if (root.getKey() instanceof String) {
                Object boxedString = root.getContext().getRealm().getEnv().asBoxedGuestValue(thisStr);
                try {
                    return interop.readMember(boxedString, (String) root.getKey());
                } catch (UnknownIdentifierException | UnsupportedMessageException e) {
                }
            }
            return Undefined.instance;
        }
    }

    public static final class JSProxyDispatcherPropertyGetNode extends LinkedPropertyGetNode {

        @Child private JSProxyPropertyGetNode proxyGet;

        @SuppressWarnings("unused")
        public JSProxyDispatcherPropertyGetNode(JSContext context, Object key, ReceiverCheckNode receiverCheck, boolean isMethod) {
            super(receiverCheck);
            this.proxyGet = JSProxyPropertyGetNode.create(context);
        }

        @Override
        protected Object getValue(Object thisObj, Object receiver, PropertyGetNode root, boolean guard) {
            return proxyGet.executeWithReceiver(receiverCheck.getStore(thisObj), receiver, root.getKey());
        }
    }

    public static final class JSProxyDispatcherRequiredPropertyGetNode extends LinkedPropertyGetNode {

        @Child private JSProxyPropertyGetNode proxyGet;
        @Child private JSProxyHasPropertyNode proxyHas;

        @SuppressWarnings("unused")
        public JSProxyDispatcherRequiredPropertyGetNode(JSContext context, Object key, ReceiverCheckNode receiverCheck, boolean isMethod) {
            super(receiverCheck);
            this.proxyGet = JSProxyPropertyGetNode.create(context);
            this.proxyHas = JSProxyHasPropertyNode.create(context);
        }

        @Override
        protected Object getValue(Object thisObj, Object receiver, PropertyGetNode root, boolean guard) {
            Object key = root.getKey();
            DynamicObject proxy = receiverCheck.getStore(thisObj);
            if (proxyHas.executeWithTargetAndKeyBoolean(proxy, key)) {
                return proxyGet.executeWithReceiver(proxy, receiver, key);
            } else {
                throw Errors.createReferenceErrorNotDefined(key, this);
            }
        }

    }

    public static final class JSAdapterPropertyGetNode extends LinkedPropertyGetNode {
        public JSAdapterPropertyGetNode(ReceiverCheckNode receiverCheck) {
            super(receiverCheck);
        }

        @Override
        protected Object getValue(Object thisObj, Object receiver, PropertyGetNode root, boolean guard) {
            Object key = root.getKey();
            if (root.isMethod()) {
                return JSObject.getMethod((DynamicObject) thisObj, key);
            } else {
                return JSObject.get((DynamicObject) thisObj, key);
            }
        }
    }

    public static final class UnspecializedPropertyGetNode extends LinkedPropertyGetNode {
        public UnspecializedPropertyGetNode(ReceiverCheckNode receiverCheck) {
            super(receiverCheck);
        }

        @Override
        protected Object getValue(Object thisObj, Object receiver, PropertyGetNode root, boolean guard) {
            return JSObject.get((DynamicObject) thisObj, root.getKey());
        }
    }

    public static final class ForeignPropertyGetNode extends LinkedPropertyGetNode {

        @Child private JSForeignToJSTypeNode toJSTypeNode;
        @Child private ForeignObjectPrototypeNode foreignObjectPrototypeNode;
        @Child private PropertyGetNode getFromPrototypeNode;
        private final boolean isLength;
        private final boolean isMethod;
        private final boolean isGlobal;
        @CompilationFinal private boolean optimistic = true;
        private final JSContext context;
        @Child private InteropLibrary interop;
        @Child private InteropLibrary getterInterop;

        public ForeignPropertyGetNode(Object key, boolean isMethod, boolean isGlobal, JSContext context) {
            super(new ForeignLanguageCheckNode());
            this.context = context;
            this.toJSTypeNode = JSForeignToJSTypeNodeGen.create();
            this.isLength = key.equals(JSAbstractArray.LENGTH);
            this.isMethod = isMethod;
            this.isGlobal = isGlobal;
            this.interop = InteropLibrary.getFactory().createDispatched(5);
        }

        private Object foreignGet(TruffleObject thisObj, PropertyGetNode root) {
            Object key = root.getKey();
            if (interop.isNull(thisObj)) {
                throw Errors.createTypeErrorCannotGetProperty(key, thisObj, isMethod, this);
            }
            if (!(key instanceof String)) {
                return Undefined.instance;
            }
            String stringKey = (String) key;
            Object foreignResult;
            if (optimistic) {
                try {
                    foreignResult = interop.readMember(thisObj, stringKey);
                } catch (UnknownIdentifierException e) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    optimistic = false;
                    if (context.isOptionNashornCompatibilityMode()) {
                        foreignResult = tryInvokeGetter(thisObj, root);
                    } else {
                        return maybeGetFromPrototype(thisObj, key);
                    }
                } catch (UnsupportedMessageException e) {
                    return maybeGetFromPrototype(thisObj, key);
                }
            } else {
                if (interop.isMemberReadable(thisObj, stringKey)) {
                    try {
                        foreignResult = interop.readMember(thisObj, stringKey);
                    } catch (UnknownIdentifierException | UnsupportedMessageException e) {
                        return Undefined.instance;
                    }
                } else if (context.isOptionNashornCompatibilityMode()) {
                    foreignResult = tryInvokeGetter(thisObj, root);
                } else {
                    return maybeGetFromPrototype(thisObj, key);
                }
            }
            return toJSTypeNode.executeWithTarget(foreignResult);
        }

        private Object maybeGetFromPrototype(TruffleObject thisObj, Object key) {
            if (context.getContextOptions().hasForeignObjectPrototype()) {
                if (getFromPrototypeNode == null || foreignObjectPrototypeNode == null) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    getFromPrototypeNode = insert(PropertyGetNode.create(key, context));
                    foreignObjectPrototypeNode = insert(ForeignObjectPrototypeNode.create());
                }
                assert key.equals(getFromPrototypeNode.getKey());
                DynamicObject prototype = foreignObjectPrototypeNode.executeDynamicObject(thisObj);
                return getFromPrototypeNode.getValue(prototype);
            }
            return Undefined.instance;
        }

        // in nashorn-compat mode, `javaObj.xyz` can mean `javaObj.getXyz()`.
        private Object tryInvokeGetter(TruffleObject thisObj, PropertyGetNode root) {
            assert context.isOptionNashornCompatibilityMode();
            TruffleLanguage.Env env = context.getRealm().getEnv();
            if (env.isHostObject(thisObj)) {
                Object result = tryGetResult(thisObj, "get", root);
                if (result != null) {
                    return result;
                }
                result = tryGetResult(thisObj, "is", root);
                if (result != null) {
                    return result;
                }
            }
            return maybeGetFromPrototype(thisObj, root.getKey());
        }

        private Object tryGetResult(TruffleObject thisObj, String prefix, PropertyGetNode root) {
            String getterKey = root.getAccessorKey(prefix);
            if (getterKey == null) {
                return null;
            }
            if (getterInterop == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                getterInterop = insert(InteropLibrary.getFactory().createDispatched(3));
            }
            if (!getterInterop.isMemberInvocable(thisObj, getterKey)) {
                return null;
            }
            try {
                return getterInterop.invokeMember(thisObj, getterKey, JSArguments.EMPTY_ARGUMENTS_ARRAY);
            } catch (UnknownIdentifierException e) {
                return null;
            } catch (UnsupportedMessageException | UnsupportedTypeException | ArityException e) {
                return Undefined.instance;
            }
        }

        private Object getSize(TruffleObject thisObj) {
            try {
                return JSRuntime.longToIntOrDouble(interop.getArraySize(thisObj));
            } catch (UnsupportedMessageException e) {
                throw Errors.createTypeErrorInteropException(thisObj, e, "getArraySize", this);
            }
        }

        @Override
        protected Object getValue(Object object, Object receiver, PropertyGetNode root, boolean guard) {
            TruffleObject thisObj = (TruffleObject) object;
            if (isMethod && !isGlobal) {
                return thisObj;
            }
            if (isLength && interop.hasArrayElements(thisObj)) {
                return getSize(thisObj);
            }
            return foreignGet(thisObj, root);
        }

    }

    @NodeInfo(cost = NodeCost.MEGAMORPHIC)
    public static class GenericPropertyGetNode extends GetCacheNode {
        @Child private JSToObjectNode toObjectNode;
        @Child private ForeignPropertyGetNode foreignGetNode;
        @Child private GetPropertyFromJSObjectNode getFromJSObjectNode;
        private final ConditionProfile isJSObject = ConditionProfile.createBinaryProfile();
        private final ConditionProfile isForeignObject = ConditionProfile.createBinaryProfile();
        private final BranchProfile notAJSObjectBranch = BranchProfile.create();
        private final BranchProfile fallbackBranch = BranchProfile.create();

        public GenericPropertyGetNode() {
            super(null);
        }

        @Override
        protected Object getValue(Object thisObj, Object receiver, PropertyGetNode root, boolean guard) {
            return getValueOrDefault(thisObj, receiver, Undefined.instance, root, guard);
        }

        @Override
        protected Object getValueOrDefault(Object thisObj, Object receiver, Object defaultValue, PropertyGetNode root, boolean guard) {
            if (isJSObject.profile(JSObject.isJSObject(thisObj))) {
                return getPropertyFromJSObject((DynamicObject) thisObj, receiver, defaultValue, root);
            } else {
                if (isForeignObject.profile(JSGuards.isForeignObject(thisObj))) {
                    // a TruffleObject from another language
                    if (foreignGetNode == null) {
                        CompilerDirectives.transferToInterpreterAndInvalidate();
                        foreignGetNode = insert(new ForeignPropertyGetNode(root.getKey(), root.isMethod(), root.isGlobal(), root.getContext()));
                    }
                    return foreignGetNode.getValue(thisObj, receiver, root, guard);
                } else {
                    // a primitive, or a Symbol
                    if (toObjectNode == null) {
                        CompilerDirectives.transferToInterpreterAndInvalidate();
                        toObjectNode = insert(JSToObjectNode.createToObjectNoCheck(root.getContext()));
                    }
                    DynamicObject object = JSRuntime.expectJSObject(toObjectNode.executeTruffleObject(thisObj), notAJSObjectBranch);
                    return getPropertyFromJSObject(object, receiver, defaultValue, root);
                }
            }
        }

        private Object getPropertyFromJSObject(DynamicObject thisObj, Object receiver, Object defaultValue, PropertyGetNode root) {
            if (root.getKey() instanceof HiddenKey) {
                Object result = thisObj.get(root.getKey());
                if (result != null) {
                    return result;
                } else {
                    fallbackBranch.enter();
                    return getFallback(thisObj, root);
                }
            } else {
                if (getFromJSObjectNode == null) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    getFromJSObjectNode = insert(GetPropertyFromJSObjectNode.create(root.getKey(), root.isRequired()));
                }
                return getFromJSObjectNode.executeWithJSObject(thisObj, receiver, defaultValue, root);
            }
        }

        protected Object getFallback(@SuppressWarnings("unused") DynamicObject thisObj, PropertyGetNode root) {
            if (root.isRequired()) {
                throw Errors.createReferenceErrorNotDefined(root.getKey(), this);
            }
            return Undefined.instance;
        }
    }

    abstract static class GetPropertyFromJSObjectNode extends JavaScriptBaseNode {
        private final Object key;
        private final boolean isRequired;
        private final BranchProfile nullOrUndefinedBranch = BranchProfile.create();
        private final BranchProfile fallbackBranch = BranchProfile.create();

        GetPropertyFromJSObjectNode(Object key, boolean isRequired) {
            this.key = key;
            this.isRequired = isRequired;
        }

        public abstract Object executeWithJSObject(DynamicObject thisObj, Object receiver, Object defaultValue, PropertyGetNode root);

        public static GetPropertyFromJSObjectNode create(Object key, boolean isRequired) {
            return GetPropertyFromJSObjectNodeGen.create(key, isRequired);
        }

        @Specialization(limit = "2", guards = {"cachedClass == getJSClass(object)"})
        protected Object doJSObjectCached(DynamicObject object, Object receiver, Object defaultValue, PropertyGetNode root,
                        @Cached("getJSClass(object)") JSClass cachedClass) {
            return getPropertyFromJSObjectIntl(cachedClass, object, receiver, defaultValue, root);
        }

        @Specialization(replaces = "doJSObjectCached")
        protected Object doJSObjectDirect(DynamicObject object, Object receiver, Object defaultValue, PropertyGetNode root) {
            return getPropertyFromJSObjectIntl(JSObject.getJSClass(object), object, receiver, defaultValue, root);
        }

        protected JSClass getJSClass(DynamicObject object) {
            return JSObject.getJSClass(object);
        }

        private Object getPropertyFromJSObjectIntl(JSClass jsclass, DynamicObject object, Object receiver, Object defaultValue, PropertyGetNode root) {
            final boolean isMethod = root.isMethod();
            assert !(key instanceof HiddenKey);
            // 0. check for null or undefined
            if (jsclass == Null.NULL_CLASS) {
                nullOrUndefinedBranch.enter();
                throw Errors.createTypeErrorCannotGetProperty(key, object, isMethod, this);
            }

            // 1. try to get a JS property
            Object value = isMethod ? jsclass.getMethodHelper(object, receiver, key) : jsclass.getHelper(object, receiver, key);
            if (value != null) {
                return value;
            }

            // 2. try to call fallback handler or return undefined
            fallbackBranch.enter();
            return getNoSuchProperty(object, defaultValue, root);
        }

        protected Object getNoSuchProperty(DynamicObject thisObj, Object defaultValue, PropertyGetNode root) {
            if (root.getContext().isOptionNashornCompatibilityMode() &&
                            (!root.getContext().getNoSuchPropertyUnusedAssumption().isValid() || (root.isMethod() && !root.getContext().getNoSuchMethodUnusedAssumption().isValid()))) {
                return getNoSuchPropertySlow(thisObj, defaultValue, root.isMethod());
            }
            return getFallback(defaultValue);
        }

        @TruffleBoundary
        private Object getNoSuchPropertySlow(DynamicObject thisObj, Object defaultValue, boolean isMethod) {
            if (!(key instanceof Symbol) && JSRuntime.isObject(thisObj) && !JSAdapter.isJSAdapter(thisObj) && !JSProxy.isProxy(thisObj)) {
                if (isMethod) {
                    Object function = JSObject.get(thisObj, JSObject.NO_SUCH_METHOD_NAME);
                    if (function != Undefined.instance) {
                        if (JSFunction.isJSFunction(function)) {
                            return callNoSuchHandler(thisObj, (DynamicObject) function, false);
                        } else {
                            return getFallback(defaultValue);
                        }
                    }
                }
                Object function = JSObject.get(thisObj, JSObject.NO_SUCH_PROPERTY_NAME);
                if (JSFunction.isJSFunction(function)) {
                    return callNoSuchHandler(thisObj, (DynamicObject) function, true);
                }
            }
            return getFallback(defaultValue);
        }

        private Object callNoSuchHandler(DynamicObject thisObj, DynamicObject function, boolean noSuchProperty) {
            // if accessing a global variable, pass undefined as `this` instead of global object.
            // only matters if callee is strict. cf. Nashorn ScriptObject.noSuch{Property,Method}.
            Object thisObject = isGlobal() ? Undefined.instance : thisObj;
            if (noSuchProperty) {
                return JSFunction.call(function, thisObject, new Object[]{key});
            } else {
                return new JSNoSuchMethodAdapter(function, key, thisObject);
            }
        }

        protected boolean isGlobal() {
            return isRequired;
        }

        protected Object getFallback(Object defaultValue) {
            if (isRequired) {
                throw Errors.createReferenceErrorNotDefined(key, this);
            }
            return defaultValue;
        }
    }

    public static final class ArrayLengthPropertyGetNode extends LinkedPropertyGetNode {
        @Child private ArrayLengthReadNode arrayLengthRead;
        @CompilationFinal private boolean longLength;

        public ArrayLengthPropertyGetNode(Property property, ReceiverCheckNode receiverCheck) {
            super(receiverCheck);
            assert JSProperty.isData(property);
            assert isArrayLengthProperty(property);
            this.arrayLengthRead = ArrayLengthReadNode.create();
        }

        @Override
        protected Object getValue(Object thisObj, Object receiver, PropertyGetNode root, boolean guard) {
            if (!longLength) {
                try {
                    return arrayLengthRead.executeInt(receiverCheck.getStore(thisObj), guard);
                } catch (UnexpectedResultException e) {
                    longLength = true;
                    return e.getResult();
                }
            } else {
                return arrayLengthRead.executeDouble(receiverCheck.getStore(thisObj), guard);
            }
        }

        @Override
        protected int getValueInt(Object thisObj, Object receiver, PropertyGetNode root, boolean guard) throws UnexpectedResultException {
            assert assertIsArray(thisObj);
            return arrayLengthRead.executeInt(receiverCheck.getStore(thisObj), guard);
        }

        @Override
        protected double getValueDouble(Object thisObj, Object receiver, PropertyGetNode root, boolean guard) {
            assert assertIsArray(thisObj);
            return arrayLengthRead.executeDouble(receiverCheck.getStore(thisObj), guard);
        }

        private boolean assertIsArray(Object thisObj) {
            DynamicObject store = receiverCheck.getStore(thisObj);
            // shape check should be sufficient to guarantee this assertion
            assert JSArray.isJSArray(store);
            return true;
        }
    }

    public static final class FunctionLengthPropertyGetNode extends LinkedPropertyGetNode {
        private final BranchProfile isBoundBranch = BranchProfile.create();
        private final JSFunction.FunctionLengthPropertyProxy property;

        public FunctionLengthPropertyGetNode(Property property, ReceiverCheckNode receiverCheck) {
            super(receiverCheck);
            assert JSProperty.isData(property);
            assert isFunctionLengthProperty(property);
            this.property = (JSFunction.FunctionLengthPropertyProxy) JSProperty.getConstantProxy(property);
        }

        @Override
        protected Object getValue(Object thisObj, Object receiver, PropertyGetNode root, boolean guard) {
            return getValueInt(thisObj, receiver, root, guard);
        }

        @Override
        protected int getValueInt(Object thisObj, Object receiver, PropertyGetNode root, boolean guard) {
            return property.getProfiled(receiverCheck.getStore(thisObj), isBoundBranch);
        }

        @Override
        protected double getValueDouble(Object thisObj, Object receiver, PropertyGetNode root, boolean guard) {
            return getValueInt(thisObj, receiver, root, guard);
        }
    }

    public static final class FunctionNamePropertyGetNode extends LinkedPropertyGetNode {
        private final BranchProfile isBoundBranch = BranchProfile.create();
        private final JSFunction.FunctionNamePropertyProxy property;

        public FunctionNamePropertyGetNode(Property property, ReceiverCheckNode receiverCheck) {
            super(receiverCheck);
            assert JSProperty.isData(property);
            assert isFunctionNameProperty(property);
            this.property = (JSFunction.FunctionNamePropertyProxy) JSProperty.getConstantProxy(property);
        }

        @Override
        protected Object getValue(Object thisObj, Object receiver, PropertyGetNode root, boolean guard) {
            return property.getProfiled(receiverCheck.getStore(thisObj), isBoundBranch);
        }
    }

    public static final class ClassPrototypePropertyGetNode extends LinkedPropertyGetNode {

        @CompilationFinal private DynamicObject constantFunction;
        @Child private CreateMethodPropertyNode setConstructor;
        @CompilationFinal private int kind;
        private final JSContext context;
        private final ConditionProfile prototypeInitializedProfile = ConditionProfile.createCountingProfile();

        private static final int UNKNOWN = 0;
        private static final int CONSTRUCTOR = 1;
        private static final int GENERATOR = 2;
        private static final int ASYNC_GENERATOR = 3;

        private static final DynamicObject UNKNOWN_FUN = Undefined.instance;
        private static final DynamicObject GENERIC_FUN = null;

        public ClassPrototypePropertyGetNode(Property property, ReceiverCheckNode receiverCheck, JSContext context) {
            super(receiverCheck);
            assert JSProperty.isData(property) && isClassPrototypeProperty(property);
            this.context = context;
            this.constantFunction = context.isMultiContext() ? GENERIC_FUN : UNKNOWN_FUN;
        }

        @Override
        protected Object getValue(Object thisObj, Object receiver, PropertyGetNode root, boolean guard) {
            DynamicObject functionObj = receiverCheck.getStore(thisObj);
            assert JSFunction.isJSFunction(functionObj);
            DynamicObject constantFun = constantFunction;
            if (constantFun == UNKNOWN_FUN) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                constantFunction = functionObj;
                // ensure `prototype` is initialized
                return JSFunction.getClassPrototype(functionObj);
            } else if (constantFun != GENERIC_FUN) {
                if (constantFun == functionObj) {
                    return JSFunction.getClassPrototypeInitialized(constantFun);
                } else {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    constantFunction = GENERIC_FUN;
                }
            }
            if (prototypeInitializedProfile.profile(JSFunction.isClassPrototypeInitialized(functionObj))) {
                return JSFunction.getClassPrototypeInitialized(functionObj);
            } else {
                return getPrototypeNotInitialized(functionObj);
            }
        }

        private Object getPrototypeNotInitialized(DynamicObject functionObj) {
            if (kind == UNKNOWN) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                JSFunctionData functionData = JSFunction.getFunctionData(functionObj);
                if (functionData.isAsyncGenerator()) {
                    kind = ASYNC_GENERATOR;
                } else if (functionData.isGenerator()) {
                    kind = GENERATOR;
                } else {
                    kind = CONSTRUCTOR;
                }
            }
            JSRealm realm = JSFunction.getRealm(functionObj, context);
            // Function kind guaranteed by shape check, see JSFunction
            DynamicObject prototype;
            if (kind == CONSTRUCTOR) {
                assert JSFunction.getFunctionData(functionObj).isConstructor();
                if (setConstructor == null) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    setConstructor = insert(CreateMethodPropertyNode.create(context, JSObject.CONSTRUCTOR));
                }
                prototype = JSUserObject.create(context, realm);
                setConstructor.executeVoid(prototype, functionObj);
            } else if (kind == GENERATOR) {
                assert JSFunction.getFunctionData(functionObj).isGenerator();
                prototype = JSObject.createWithRealm(context, context.getGeneratorObjectFactory(), realm);
            } else {
                assert kind == ASYNC_GENERATOR;
                assert JSFunction.getFunctionData(functionObj).isAsyncGenerator();
                prototype = JSObject.createWithRealm(context, context.getAsyncGeneratorObjectFactory(), realm);
            }
            JSFunction.setClassPrototype(functionObj, prototype);
            return prototype;
        }
    }

    public static final class StringLengthPropertyGetNode extends LinkedPropertyGetNode {
        private final ValueProfile charSequenceClassProfile = ValueProfile.createClassProfile();

        public StringLengthPropertyGetNode(Property property, ReceiverCheckNode receiverCheck) {
            super(receiverCheck);
            assert JSProperty.isData(property);
            assert isStringLengthProperty(property);
        }

        @Override
        protected Object getValue(Object thisObj, Object receiver, PropertyGetNode root, boolean guard) {
            return getValueInt(thisObj, receiver, root, guard);
        }

        @Override
        protected int getValueInt(Object thisObj, Object receiver, PropertyGetNode root, boolean guard) {
            CharSequence charSequence = JSString.getCharSequence(receiverCheck.getStore(thisObj));
            return JSRuntime.length(charSequenceClassProfile.profile(charSequence));
        }

        @Override
        protected double getValueDouble(Object thisObj, Object receiver, PropertyGetNode root, boolean guard) {
            return getValueInt(thisObj, receiver, root, guard);
        }
    }

    public static final class LazyRegexResultIndexPropertyGetNode extends LinkedPropertyGetNode {

        @Child private TRegexUtil.InvokeGetGroupBoundariesMethodNode readStartNode = TRegexUtil.InvokeGetGroupBoundariesMethodNode.create();

        public LazyRegexResultIndexPropertyGetNode(Property property, ReceiverCheckNode receiverCheck) {
            super(receiverCheck);
            assert JSProperty.isData(property);
            assert isLazyRegexResultIndexProperty(property);
        }

        @Override
        protected Object getValue(Object thisObj, Object receiver, PropertyGetNode root, boolean guard) {
            return getValueInt(thisObj, receiver, root, guard);
        }

        @Override
        protected int getValueInt(Object thisObj, Object receiver, PropertyGetNode root, boolean guard) {
            return readStartNode.execute(arrayGetRegexResult(receiverCheck.getStore(thisObj), guard), TRegexUtil.Props.RegexResult.GET_START, 0);
        }

        @Override
        protected double getValueDouble(Object thisObj, Object receiver, PropertyGetNode root, boolean guard) {
            return getValueInt(thisObj, receiver, root, guard);
        }
    }

    public static final class LazyNamedCaptureGroupPropertyGetNode extends LinkedPropertyGetNode {

        private final int groupIndex;
        @Child private PropertyGetNode getResultNode;
        @Child private PropertyGetNode getOriginalInputNode;
        @Child private TRegexMaterializeResultNode materializeNode = TRegexMaterializeResultNode.create();

        public LazyNamedCaptureGroupPropertyGetNode(Property property, ReceiverCheckNode receiverCheck, JSContext context, int groupIndex) {
            super(receiverCheck);
            assert isLazyNamedCaptureGroupProperty(property);
            this.groupIndex = groupIndex;
            this.getResultNode = PropertyGetNode.create(JSRegExp.GROUPS_RESULT_ID, false, context);
            this.getOriginalInputNode = PropertyGetNode.create(JSRegExp.GROUPS_ORIGINAL_INPUT_ID, false, context);
        }

        @Override
        protected Object getValue(Object thisObj, Object receiver, PropertyGetNode root, boolean guard) {
            DynamicObject store = receiverCheck.getStore(thisObj);
            TruffleObject regexResult = (TruffleObject) getResultNode.getValue(store);
            String input = (String) getOriginalInputNode.getValue(store);
            return materializeNode.materializeGroup(regexResult, groupIndex, input);
        }
    }

    /**
     * Make a cache for a JSObject with this property map and requested property.
     *
     * @param property The particular entry of the property being accessed.
     */
    @Override
    protected GetCacheNode createCachedPropertyNode(Property property, Object thisObj, int depth, Object value, GetCacheNode currentHead) {
        assert !isOwnProperty() || depth == 0;
        if (!(JSObject.isDynamicObject(thisObj))) {
            return createCachedPropertyNodeNotJSObject(property, thisObj, depth);
        }

        Shape cacheShape = ((DynamicObject) thisObj).getShape();

        if ((JSProperty.isData(property) && !JSProperty.isProxy(property) || JSProperty.isAccessor(property)) &&
                        (property.getLocation().isFinal() || property.getLocation().isAssumedFinal())) {
            /**
             * if property is final and: <br>
             * (1) shape not in cache: specialize on final property with constant object [prototype
             * [chain]] shape check. <br>
             * (2) shape already in cache: if cache entry is constant object prototype [chain] shape
             * check, evict cache entry and specialize on final property with normal shape check,
             * otherwise go to (3). <br>
             *
             * (3) evict cache entry and treat property as non-final.
             */
            boolean isConstantObjectFinal = isPropertyAssumptionCheckEnabled();
            for (GetCacheNode cur = currentHead; cur != null; cur = cur.next) {
                if (isFinalSpecialization(cur)) {
                    if (cur.receiverCheck instanceof ConstantObjectReceiverCheck) {
                        // invalidate the specialization and disable constant object checks
                        ((ConstantObjectReceiverCheck) cur.receiverCheck).clearExpectedObject();
                        setPropertyAssumptionCheckEnabled(false);
                        return null; // clean up cache
                    }
                    assert !(cur.receiverCheck instanceof ConstantObjectReceiverCheck) || ((ConstantObjectReceiverCheck) cur.receiverCheck).getExpectedObject() == thisObj;
                }
            }

            if (JSProperty.isData(property) && !JSProperty.isProxy(property)) {
                if (isEligibleForFinalSpecialization(cacheShape, (DynamicObject) thisObj, depth, isConstantObjectFinal)) {
                    return createFinalSpecialization(property, cacheShape, (DynamicObject) thisObj, depth, isConstantObjectFinal);
                }
            } else if (JSProperty.isAccessor(property)) {
                if (isEligibleForFinalSpecialization(cacheShape, (DynamicObject) thisObj, depth, isConstantObjectFinal)) {
                    return createFinalAccessorSpecialization(property, cacheShape, (DynamicObject) thisObj, depth, isConstantObjectFinal);
                }
            }
        }

        AbstractShapeCheckNode shapeCheck = createShapeCheckNode(cacheShape, (DynamicObject) thisObj, depth, false, false);
        if (JSProperty.isData(property)) {
            return createSpecializationFromDataProperty(property, shapeCheck, context);
        } else {
            assert JSProperty.isAccessor(property);
            return new AccessorPropertyGetNode(property, shapeCheck);
        }
    }

    private static boolean isFinalSpecialization(GetCacheNode existingNode) {
        return existingNode instanceof AbstractFinalDataPropertyGetNode || existingNode instanceof FinalAccessorPropertyGetNode;
    }

    private boolean isEligibleForFinalSpecialization(Shape cacheShape, DynamicObject thisObj, int depth, boolean isConstantObjectFinal) {
        /*
         * NB: We need to check whether the property assumption of the store is valid, even if we do
         * not actually check the assumption in the specialization but check the shape instead (note
         * that we always check the expected object instance, too, either directly (depth 0) or
         * indirectly (prototype derived through shape or property assumption for depth >= 1)). This
         * is because we cannot guarantee a final location value to be constant for (object, shape)
         * anymore once the assumption has been invalidated. Namely, one could remove and re-add a
         * property without invalidating its finality. Perhaps we should invalidate the finality of
         * removed properties. For now, we have to be conservative.
         */
        return depth >= 1 ? (prototypesInShape(thisObj, depth) && propertyAssumptionsValid(thisObj, depth, isConstantObjectFinal))
                        : (JSTruffleOptions.SkipFinalShapeCheck && isPropertyAssumptionCheckEnabled() && JSShape.getPropertyAssumption(cacheShape, key).isValid());
    }

    private GetCacheNode createCachedPropertyNodeNotJSObject(Property property, Object thisObj, int depth) {
        final ReceiverCheckNode receiverCheck;
        if (depth == 0) {
            if (isMethod() && thisObj instanceof String && context.isOptionNashornCompatibilityMode()) {
                // This hack ensures we get the Java method instead of the JavaScript property
                // for length in s.length() where s is a java.lang.String. Required by Nashorn.
                // We do this only for depth 0, because JavaScript prototype functions in turn
                // are preferred over Java methods with the same name.
                GetCacheNode javaPropertyNode = createJavaPropertyNodeMaybe(thisObj, depth);
                if (javaPropertyNode != null) {
                    return javaPropertyNode;
                }
            }

            receiverCheck = new InstanceofCheckNode(thisObj.getClass(), context);
        } else {
            receiverCheck = createPrimitiveReceiverCheck(thisObj, depth);
        }

        if (JSProperty.isData(property)) {
            return createSpecializationFromDataProperty(property, receiverCheck, context);
        } else {
            assert JSProperty.isAccessor(property);
            return new AccessorPropertyGetNode(property, receiverCheck);
        }
    }

    private static GetCacheNode createSpecializationFromDataProperty(Property property, ReceiverCheckNode receiverCheck, JSContext context) {
        Property dataProperty = property;

        if (property.getLocation() instanceof IntLocation) {
            return new IntPropertyGetNode(dataProperty, receiverCheck);
        } else if (property.getLocation() instanceof DoubleLocation) {
            return new DoublePropertyGetNode(dataProperty, receiverCheck);
        } else if (property.getLocation() instanceof BooleanLocation) {
            return new BooleanPropertyGetNode(dataProperty, receiverCheck);
        } else if (property.getLocation() instanceof LongLocation) {
            return new LongPropertyGetNode(dataProperty, receiverCheck);
        } else {
            if (isArrayLengthProperty(property)) {
                return new ArrayLengthPropertyGetNode(dataProperty, receiverCheck);
            } else if (isFunctionLengthProperty(property)) {
                return new FunctionLengthPropertyGetNode(dataProperty, receiverCheck);
            } else if (isFunctionNameProperty(property)) {
                return new FunctionNamePropertyGetNode(dataProperty, receiverCheck);
            } else if (isClassPrototypeProperty(property)) {
                return new ClassPrototypePropertyGetNode(dataProperty, receiverCheck, context);
            } else if (isStringLengthProperty(property)) {
                return new StringLengthPropertyGetNode(dataProperty, receiverCheck);
            } else if (isLazyRegexResultIndexProperty(property)) {
                return new LazyRegexResultIndexPropertyGetNode(dataProperty, receiverCheck);
            } else if (isLazyNamedCaptureGroupProperty(property)) {
                int groupIndex = ((JSRegExp.LazyNamedCaptureGroupProperty) JSProperty.getConstantProxy(property)).getGroupIndex();
                return new LazyNamedCaptureGroupPropertyGetNode(dataProperty, receiverCheck, context, groupIndex);
            } else {
                return new ObjectPropertyGetNode(dataProperty, receiverCheck);
            }
        }
    }

    private GetCacheNode createFinalSpecialization(Property property, Shape cacheShape, DynamicObject thisObj, int depth, boolean isConstantObjectFinal) {
        AbstractShapeCheckNode finalShapeCheckNode = createShapeCheckNode(cacheShape, thisObj, depth, isConstantObjectFinal, false);
        finalShapeCheckNode.adoptChildren();
        DynamicObject store = finalShapeCheckNode.getStore(thisObj);
        return createFinalSpecializationImpl(property, finalShapeCheckNode, store);
    }

    private static GetCacheNode createFinalSpecializationImpl(Property property, AbstractShapeCheckNode shapeCheckNode, DynamicObject store) {
        if (property.getLocation() instanceof IntLocation) {
            return new FinalIntPropertyGetNode(property, shapeCheckNode, ((IntLocation) property.getLocation()).getInt(store, false));
        } else if (property.getLocation() instanceof DoubleLocation) {
            return new FinalDoublePropertyGetNode(property, shapeCheckNode, ((DoubleLocation) property.getLocation()).getDouble(store, false));
        } else if (property.getLocation() instanceof BooleanLocation) {
            return new FinalBooleanPropertyGetNode(property, shapeCheckNode, ((BooleanLocation) property.getLocation()).getBoolean(store, false));
        } else if (property.getLocation() instanceof LongLocation) {
            return new FinalLongPropertyGetNode(property, shapeCheckNode, ((LongLocation) property.getLocation()).getLong(store, false));
        } else {
            assert property.getLocation() instanceof ObjectLocation;
            return new FinalObjectPropertyGetNode(property, shapeCheckNode, property.get(store, false));
        }
    }

    private GetCacheNode createFinalAccessorSpecialization(Property property, Shape cacheShape, DynamicObject thisObj, int depth, boolean isConstantObjectFinal) {
        AbstractShapeCheckNode finalShapeCheckNode = createShapeCheckNode(cacheShape, thisObj, depth, isConstantObjectFinal, false);
        finalShapeCheckNode.adoptChildren();
        DynamicObject store = finalShapeCheckNode.getStore(thisObj);
        Accessor accessor = (Accessor) property.get(store, null);
        return new FinalAccessorPropertyGetNode(property, finalShapeCheckNode, accessor);
    }

    @Override
    protected GetCacheNode createJavaPropertyNodeMaybe(Object thisObj, int depth) {
        if (JavaPackage.isJavaPackage(thisObj)) {
            return new JavaPackagePropertyGetNode(new JSClassCheckNode(JSObject.getJSClass((DynamicObject) thisObj)));
        } else if (JavaImporter.isJavaImporter(thisObj)) {
            return new UnspecializedPropertyGetNode(new JSClassCheckNode(JSObject.getJSClass((DynamicObject) thisObj)));
        }
        if (JSTruffleOptions.SubstrateVM) {
            return null;
        }
        if (context.isOptionNashornCompatibilityMode() && context.getRealm().isJavaInteropEnabled()) {
            if (thisObj instanceof String && isMethod()) {
                return new JavaStringMethodGetNode(createPrimitiveReceiverCheck(thisObj, depth));
            }
        }
        return null;
    }

    @Override
    protected GetCacheNode createUndefinedPropertyNode(Object thisObj, Object store, int depth, Object value) {
        GetCacheNode javaPropertyNode = createJavaPropertyNodeMaybe(thisObj, depth);
        if (javaPropertyNode != null) {
            return javaPropertyNode;
        }

        if (JSObject.isDynamicObject(thisObj)) {
            DynamicObject jsobject = (DynamicObject) thisObj;
            Shape cacheShape = jsobject.getShape();
            AbstractShapeCheckNode shapeCheck = createShapeCheckNode(cacheShape, jsobject, depth, false, false);
            ReceiverCheckNode receiverCheck = (depth == 0) ? new JSClassCheckNode(JSObject.getJSClass(jsobject)) : shapeCheck;
            if (JSAdapter.isJSAdapter(store)) {
                return new JSAdapterPropertyGetNode(receiverCheck);
            } else if (JSProxy.isProxy(store) && !(key instanceof HiddenKey)) {
                if (isRequired()) {
                    return new JSProxyDispatcherRequiredPropertyGetNode(context, key, receiverCheck, isMethod());
                } else {
                    return new JSProxyDispatcherPropertyGetNode(context, key, receiverCheck, isMethod());
                }
            } else if (JSModuleNamespace.isJSModuleNamespace(store)) {
                return new UnspecializedPropertyGetNode(receiverCheck);
            } else if (JSArrayBufferView.isJSArrayBufferView(store) && isNonIntegerIndex(key)) {
                return new ArrayBufferViewNonIntegerIndexGetNode(shapeCheck);
            } else {
                return createUndefinedJSObjectPropertyNode(jsobject, depth);
            }
        } else if (JSProxy.isProxy(store)) {
            ReceiverCheckNode receiverCheck = createPrimitiveReceiverCheck(thisObj, depth);
            return new JSProxyDispatcherPropertyGetNode(context, key, receiverCheck, isMethod());
        } else {
            if (thisObj == null) {
                return new TypeErrorPropertyGetNode(new NullCheckNode());
            } else {
                ReceiverCheckNode receiverCheck = createPrimitiveReceiverCheck(thisObj, depth);
                return createUndefinedOrErrorPropertyNode(receiverCheck);
            }
        }
    }

    private GetCacheNode createUndefinedJSObjectPropertyNode(DynamicObject jsobject, int depth) {
        AbstractShapeCheckNode shapeCheck = createShapeCheckNode(jsobject.getShape(), jsobject, depth, false, false);
        if (JSRuntime.isObject(jsobject)) {
            if (context.isOptionNashornCompatibilityMode() && !(key instanceof Symbol)) {
                if ((!context.getNoSuchMethodUnusedAssumption().isValid() && JSObject.hasProperty(jsobject, JSObject.NO_SUCH_METHOD_NAME)) ||
                                (!context.getNoSuchPropertyUnusedAssumption().isValid() && JSObject.hasProperty(jsobject, JSObject.NO_SUCH_PROPERTY_NAME))) {
                    return new CheckNoSuchPropertyNode(key, shapeCheck, context);
                }
            }
            return createUndefinedOrErrorPropertyNode(shapeCheck);
        } else {
            return new TypeErrorPropertyGetNode(shapeCheck);
        }
    }

    private GetCacheNode createUndefinedOrErrorPropertyNode(ReceiverCheckNode receiverCheck) {
        if (isRequired()) {
            return new UndefinedPropertyErrorNode(receiverCheck);
        } else {
            return new UndefinedPropertyGetNode(receiverCheck);
        }
    }

    /**
     * Make a generic-case node, for when polymorphism becomes too high.
     */
    @Override
    protected GetCacheNode createGenericPropertyNode() {
        return new GenericPropertyGetNode();
    }

    protected final boolean isRequired() {
        return isGlobal();
    }

    @Override
    protected final boolean isGlobal() {
        return isGlobal;
    }

    @Override
    protected final boolean isOwnProperty() {
        return getOwnProperty;
    }

    protected boolean isMethod() {
        return isMethod;
    }

    protected void setMethod() {
        CompilerAsserts.neverPartOfCompilation();
        this.isMethod = true;
    }

    @Override
    protected boolean isPropertyAssumptionCheckEnabled() {
        return propertyAssumptionCheckEnabled && getContext().isSingleRealm();
    }

    @Override
    protected void setPropertyAssumptionCheckEnabled(boolean value) {
        CompilerAsserts.neverPartOfCompilation();
        this.propertyAssumptionCheckEnabled = value;
    }

    @Override
    protected GetCacheNode createTruffleObjectPropertyNode(TruffleObject thisObject) {
        return new ForeignPropertyGetNode(key, isMethod(), isGlobal(), context);
    }
}
