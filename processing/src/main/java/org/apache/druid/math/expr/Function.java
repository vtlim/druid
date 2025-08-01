/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.math.expr;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import it.unimi.dsi.fastutil.objects.ObjectAVLTreeSet;
import it.unimi.dsi.fastutil.objects.ObjectArrays;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import org.apache.druid.error.DruidException;
import org.apache.druid.java.util.common.DateTimes;
import org.apache.druid.java.util.common.HumanReadableBytes;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.java.util.common.UOE;
import org.apache.druid.math.expr.vector.CastToTypeVectorProcessor;
import org.apache.druid.math.expr.vector.ExprVectorProcessor;
import org.apache.druid.math.expr.vector.VectorConditionalProcessors;
import org.apache.druid.math.expr.vector.VectorMathProcessors;
import org.apache.druid.math.expr.vector.VectorProcessors;
import org.apache.druid.math.expr.vector.VectorStringProcessors;
import org.apache.druid.query.filter.ColumnIndexSelector;
import org.apache.druid.segment.column.ColumnIndexSupplier;
import org.apache.druid.segment.column.ColumnType;
import org.apache.druid.segment.column.TypeSignature;
import org.apache.druid.segment.index.BitmapColumnIndex;
import org.apache.druid.segment.index.semantic.ValueSetIndexes;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.format.DateTimeFormat;

import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BinaryOperator;
import java.util.function.DoubleBinaryOperator;
import java.util.function.LongBinaryOperator;
import java.util.stream.Collectors;

/**
 * Base interface describing the mechanism used to evaluate a {@link FunctionExpr}. All {@link Function} implementations
 * are immutable.
 *
 * Do NOT remove "unused" members in this class. They are used by generated Antlr
 */
@SuppressWarnings("unused")
public interface Function extends NamedFunction
{
  /**
   * Possibly convert a {@link Function} into an optimized, possibly not thread-safe {@link Function}.
   */
  default Function asSingleThreaded(List<Expr> args, Expr.InputBindingInspector inspector)
  {
    return this;
  }

  /**
   * Evaluate the function, given a list of arguments and a set of bindings to provide values for {@link IdentifierExpr}.
   */
  ExprEval apply(List<Expr> args, Expr.ObjectBinding bindings);

  /**
   * Given a list of arguments to this {@link Function}, get the set of arguments that must evaluate to a scalar value
   */
  default Set<Expr> getScalarInputs(List<Expr> args)
  {
    return ImmutableSet.copyOf(args);
  }

  /**
   * Given a list of arguments to this {@link Function}, get the set of arguments that must evaluate to an array
   * value
   */
  default Set<Expr> getArrayInputs(List<Expr> args)
  {
    return Collections.emptySet();
  }

  /**
   * Returns true if a function expects any array arguments
   */
  default boolean hasArrayInputs()
  {
    return false;
  }

  /**
   * Returns true if function produces an array. All {@link Function} implementations are expected to
   * exclusively produce either scalar or array values.
   */
  default boolean hasArrayOutput()
  {
    return false;
  }

  /**
   * Validate function arguments. This method is called whenever a {@link FunctionExpr} is created, and should validate
   * everything that is feasible up front. Note that input type information is typically unavailable at the time
   * {@link Expr} are parsed, and so this method is incapable of performing complete validation.
   */
  void validateArguments(List<Expr> args);


  /**
   * Compute the output type of this function for a given set of argument expression inputs.
   *
   * @see Expr#getOutputType
   */
  @Nullable
  ExpressionType getOutputType(Expr.InputBindingInspector inspector, List<Expr> args);

  /**
   * Check if a function can be 'vectorized', for a given set of {@link Expr} inputs. If this method returns true,
   * {@link #asVectorProcessor} is expected to produce a {@link ExprVectorProcessor} which can evaluate values in
   * batches to use with vectorized query engines.
   *
   * @see Expr#canVectorize(Expr.InputBindingInspector)
   * @see ApplyFunction#canVectorize(Expr.InputBindingInspector, Expr, List)
   */
  default boolean canVectorize(Expr.InputBindingInspector inspector, List<Expr> args)
  {
    return false;
  }

  /**
   * Builds a 'vectorized' function expression processor, that can build vectorized processors for its input values
   * using {@link Expr#asVectorProcessor}, for use in vectorized query engines.
   *
   * @see Expr#asVectorProcessor(Expr.VectorInputBindingInspector)
   * @see ApplyFunction#asVectorProcessor(Expr.VectorInputBindingInspector, Expr, List)
   */
  default <T> ExprVectorProcessor<T> asVectorProcessor(Expr.VectorInputBindingInspector inspector, List<Expr> args)
  {
    throw new UOE("Function[%s] is not vectorized", name());
  }

  /**
   * Allows a {@link Function} to provide an {@link ColumnIndexSupplier} given access to the underlying
   * {@link ColumnIndexSupplier} and {@link org.apache.druid.segment.column.ColumnHolder} of the base table.
   *
   * Unlike the contracts of other index supplier methods, a null return value for this method is not indicative of
   * anything other than the {@link Function} implementation having no specialized index supplier implementation, and
   * so callers can fall back to generic handling as appropriate.
   *
   * @see Expr#asColumnIndexSupplier(ColumnIndexSelector, ColumnType) 
   */
  @Nullable
  default ColumnIndexSupplier asColumnIndexSupplier(
      ColumnIndexSelector selector,
      @Nullable ColumnType outputType,
      List<Expr> args
  )
  {
    return null;
  }

  /**
   * Allows a {@link Function} to be computed into a {@link BitmapColumnIndex}. The supplied {@link ColumnIndexSelector}
   * provides access to underlying {@link ColumnIndexSupplier} and even
   * {@link org.apache.druid.segment.column.ColumnHolder}. Coupled with
   * {@link #asColumnIndexSupplier(ColumnIndexSelector, ColumnType, List<Expr>)}, which allows {@link Function} to
   * provide indexes of their own, it allows for a system of composing indexes on top of any base column structures.
   *
   * Unlike {@link Expr#asBitmapColumnIndex(ColumnIndexSelector)}, a null return value of this method is not indicative
   * of anything other than the {@link Function} implementation not having a specialized way to compute into a
   * {@link BitmapColumnIndex}, and so callers can fall back to generic handling as appropriate.
   *
   * @see Expr#asBitmapColumnIndex(ColumnIndexSelector)
   */
  @Nullable
  default BitmapColumnIndex asBitmapColumnIndex(ColumnIndexSelector selector, List<Expr> args)
  {
    return null;
  }

  /**
   * Base class for a single variable input {@link Function} implementation
   */
  abstract class UnivariateFunction implements Function
  {
    @Override
    public void validateArguments(List<Expr> args)
    {
      validationHelperCheckArgumentCount(args, 1);
    }

    @Override
    public ExprEval apply(List<Expr> args, Expr.ObjectBinding bindings)
    {
      Expr expr = args.get(0);
      return eval(expr.eval(bindings));
    }

    protected abstract ExprEval eval(ExprEval param);
  }

  /**
   * Base class for a 2 variable input {@link Function} implementation
   */
  abstract class BivariateFunction implements Function
  {
    @Override
    public void validateArguments(List<Expr> args)
    {
      validationHelperCheckArgumentCount(args, 2);
    }

    @Override
    public ExprEval apply(List<Expr> args, Expr.ObjectBinding bindings)
    {
      Expr expr1 = args.get(0);
      Expr expr2 = args.get(1);
      return eval(expr1.eval(bindings), expr2.eval(bindings));
    }

    protected abstract ExprEval eval(ExprEval x, ExprEval y);
  }

  /**
   * Base class for a single variable input mathematical {@link Function}, with specialized 'eval' implementations that
   * that operate on primitive number types
   */
  abstract class UnivariateMathFunction extends UnivariateFunction
  {
    @Override
    protected final ExprEval eval(ExprEval param)
    {
      if (param.isNumericNull()) {
        return ExprEval.of(null);
      }
      if (param.type().is(ExprType.LONG)) {
        return eval(param.asLong());
      } else if (param.type().is(ExprType.DOUBLE)) {
        return eval(param.asDouble());
      }
      return ExprEval.of(null);
    }

    protected ExprEval eval(long param)
    {
      return eval((double) param);
    }

    protected ExprEval eval(double param)
    {
      if (param < Long.MIN_VALUE || param > Long.MAX_VALUE) {
        throw validationFailed(
            "Possible data truncation, param [%f] is out of LONG value range",
            param
        );
      }
      return eval((long) param);
    }

    @Nullable
    @Override
    public ExpressionType getOutputType(Expr.InputBindingInspector inspector, List<Expr> args)
    {
      return args.get(0).getOutputType(inspector);
    }

    @Override
    public boolean canVectorize(Expr.InputBindingInspector inspector, List<Expr> args)
    {
      final ExpressionType outputType = args.get(0).getOutputType(inspector);
      return (outputType == null || outputType.isNumeric()) && inspector.canVectorize(args);
    }
  }

  /**
   * Many math functions always output a {@link Double} primitive, regardless of input type.
   */
  abstract class DoubleUnivariateMathFunction extends UnivariateMathFunction
  {
    @Nullable
    @Override
    public ExpressionType getOutputType(Expr.InputBindingInspector inspector, List<Expr> args)
    {
      return ExpressionType.DOUBLE;
    }
  }

  /**
   * Base class for a 2 variable input mathematical {@link Function}, with specialized 'eval' implementations that
   * operate on primitive number types
   */
  abstract class BivariateMathFunction extends BivariateFunction
  {
    @Override
    protected final ExprEval eval(ExprEval x, ExprEval y)
    {
      // match the logic of BinaryEvalOpExprBase.eval, except there is no string handling so both strings is also null
      if (x.value() == null || y.value() == null) {
        return ExprEval.of(null);
      }

      ExpressionType type = ExpressionTypeConversion.autoDetect(x, y);
      switch (type.getType()) {
        case STRING:
          return ExprEval.of(null);
        case LONG:
          return eval(x.asLong(), y.asLong());
        case DOUBLE:
        default:
          return eval(x.asDouble(), y.asDouble());
      }
    }

    protected ExprEval eval(long x, long y)
    {
      return eval((double) x, (double) y);
    }

    protected ExprEval eval(double x, double y)
    {
      return eval((long) x, (long) y);
    }

    @Nullable
    @Override
    public ExpressionType getOutputType(Expr.InputBindingInspector inspector, List<Expr> args)
    {
      return ExpressionTypeConversion.function(
          args.get(0).getOutputType(inspector),
          args.get(1).getOutputType(inspector)
      );
    }

    @Override
    public boolean canVectorize(Expr.InputBindingInspector inspector, List<Expr> args)
    {
      return inspector.areNumeric(args) && inspector.canVectorize(args);
    }
  }

  /**
   * Many math functions always output a {@link Double} primitive, regardless of input type.
   */
  abstract class DoubleBivariateMathFunction extends BivariateMathFunction
  {
    @Nullable
    @Override
    public ExpressionType getOutputType(Expr.InputBindingInspector inspector, List<Expr> args)
    {
      return ExpressionType.DOUBLE;
    }
  }

  abstract class BivariateBitwiseMathFunction extends BivariateFunction
  {
    @Override
    protected final ExprEval eval(ExprEval x, ExprEval y)
    {
      // this is a copy of the logic of BivariateMathFunction for string handling, which itself is a
      // remix of BinaryEvalOpExprBase.eval modified so that string inputs are always null outputs
      if (x.value() == null || y.value() == null) {
        return ExprEval.of(null);
      }

      ExpressionType type = ExpressionTypeConversion.autoDetect(x, y);
      if (type.is(ExprType.STRING)) {
        return ExprEval.of(null);
      }
      return eval(x.asLong(), y.asLong());
    }

    protected abstract ExprEval eval(long x, long y);

    @Nullable
    @Override
    public ExpressionType getOutputType(Expr.InputBindingInspector inspector, List<Expr> args)
    {
      return ExpressionType.LONG;
    }

    @Override
    public boolean canVectorize(Expr.InputBindingInspector inspector, List<Expr> args)
    {
      return inspector.areNumeric(args) && inspector.canVectorize(args);
    }
  }

  /**
   * Base class for a 2 variable input {@link Function} whose first argument is a {@link ExprType#STRING} and second
   * argument is {@link ExprType#LONG}. These functions return null if either argument is null.
   */
  abstract class StringLongFunction extends BivariateFunction
  {
    @Override
    protected final ExprEval eval(ExprEval x, ExprEval y)
    {
      final String xString = x.asString();
      if (xString == null) {
        return ExprEval.of(null);
      }
      if (y.isNumericNull()) {
        return ExprEval.of(null);
      }
      return eval(xString, y.asLong());
    }

    protected abstract ExprEval eval(String x, long y);
  }

  /**
   * {@link Function} that takes 1 array operand and 1 scalar operand
   */
  abstract class ArrayScalarFunction implements Function
  {
    @Override
    public void validateArguments(List<Expr> args)
    {
      validationHelperCheckArgumentCount(args, 2);
    }

    @Override
    public Set<Expr> getScalarInputs(List<Expr> args)
    {
      return ImmutableSet.of(getScalarArgument(args));
    }

    @Override
    public Set<Expr> getArrayInputs(List<Expr> args)
    {
      return ImmutableSet.of(getArrayArgument(args));
    }

    @Override
    public boolean hasArrayInputs()
    {
      return true;
    }

    @Override
    public ExprEval apply(List<Expr> args, Expr.ObjectBinding bindings)
    {
      final ExprEval arrayExpr = getArrayArgument(args).eval(bindings);
      final ExprEval scalarExpr = getScalarArgument(args).eval(bindings);
      if (arrayExpr.asArray() == null) {
        return ExprEval.of(null);
      }
      return doApply(arrayExpr, scalarExpr);
    }

    Expr getScalarArgument(List<Expr> args)
    {
      return args.get(1);
    }

    Expr getArrayArgument(List<Expr> args)
    {
      return args.get(0);
    }

    abstract ExprEval doApply(ExprEval arrayExpr, ExprEval scalarExpr);
  }

  /**
   * {@link Function} that takes 2 array operands
   */
  abstract class ArraysFunction implements Function
  {
    @Override
    public void validateArguments(List<Expr> args)
    {
      validationHelperCheckArgumentCount(args, 2);
    }

    @Override
    public Set<Expr> getScalarInputs(List<Expr> args)
    {
      return Collections.emptySet();
    }

    @Override
    public Set<Expr> getArrayInputs(List<Expr> args)
    {
      return ImmutableSet.copyOf(args);
    }

    @Override
    public boolean hasArrayInputs()
    {
      return true;
    }

    @Override
    public ExprEval apply(List<Expr> args, Expr.ObjectBinding bindings)
    {
      final ExprEval arrayExpr1 = args.get(0).eval(bindings);
      final ExprEval arrayExpr2 = args.get(1).eval(bindings);

      if (arrayExpr1.asArray() == null) {
        return arrayExpr1;
      }
      if (arrayExpr2.asArray() == null) {
        return arrayExpr2;
      }

      return doApply(arrayExpr1, arrayExpr2);
    }

    abstract ExprEval doApply(ExprEval lhsExpr, ExprEval rhsExpr);
  }

  /**
   * Scaffolding for a 2 argument {@link Function} which accepts one array and one scalar input and adds the scalar
   * input to the array in some way.
   */
  abstract class ArrayAddElementFunction extends ArrayScalarFunction
  {
    @Override
    public boolean hasArrayOutput()
    {
      return true;
    }

    @Nullable
    @Override
    public ExpressionType getOutputType(Expr.InputBindingInspector inspector, List<Expr> args)
    {
      ExpressionType arrayType = getArrayArgument(args).getOutputType(inspector);
      return Optional.ofNullable(ExpressionType.asArrayType(arrayType)).orElse(arrayType);
    }

    @Override
    ExprEval doApply(ExprEval arrayExpr, ExprEval scalarExpr)
    {
      final ExpressionType arrayType = arrayExpr.asArrayType();
      if (!scalarExpr.type().equals(arrayExpr.elementType())) {
        // try to cast
        ExprEval coerced = scalarExpr.castTo(arrayExpr.elementType());
        return ExprEval.ofArray(arrayType, add(arrayType.getElementType(), arrayExpr.asArray(), coerced.value()));
      }

      return ExprEval.ofArray(arrayType, add(arrayType.getElementType(), arrayExpr.asArray(), scalarExpr.value()));
    }

    abstract <T> Object[] add(TypeSignature<ExprType> elementType, T[] array, @Nullable T val);
  }

  /**
   * Base scaffolding for functions which accept 2 array arguments and combine them in some way
   */
  abstract class ArraysMergeFunction extends ArraysFunction
  {

    @Override
    public boolean hasArrayOutput()
    {
      return true;
    }

    @Nullable
    @Override
    public ExpressionType getOutputType(Expr.InputBindingInspector inspector, List<Expr> args)
    {
      ExpressionType arrayType = args.get(0).getOutputType(inspector);
      return Optional.ofNullable(ExpressionType.asArrayType(arrayType)).orElse(arrayType);
    }

    @Override
    ExprEval doApply(ExprEval lhsExpr, ExprEval rhsExpr)
    {
      final Object[] array1 = lhsExpr.asArray();
      final Object[] array2 = rhsExpr.asArray();

      if (array1 == null) {
        return ExprEval.of(null);
      }
      if (array2 == null) {
        return lhsExpr;
      }

      final ExpressionType arrayType = lhsExpr.asArrayType();

      if (!lhsExpr.asArrayType().equals(rhsExpr.asArrayType())) {
        // try to cast if they types don't match
        ExprEval coerced = rhsExpr.castTo(arrayType);
        ExprEval.ofArray(arrayType, merge(arrayType.getElementType(), lhsExpr.asArray(), coerced.asArray()));
      }

      return ExprEval.ofArray(arrayType, merge(arrayType.getElementType(), lhsExpr.asArray(), rhsExpr.asArray()));
    }

    abstract <T> Object[] merge(TypeSignature<ExprType> elementType, T[] array1, T[] array2);
  }

  abstract class ReduceFunction implements Function
  {
    private final DoubleBinaryOperator doubleReducer;
    private final LongBinaryOperator longReducer;
    private final BinaryOperator<String> stringReducer;

    ReduceFunction(
        DoubleBinaryOperator doubleReducer,
        LongBinaryOperator longReducer,
        BinaryOperator<String> stringReducer
    )
    {
      this.doubleReducer = doubleReducer;
      this.longReducer = longReducer;
      this.stringReducer = stringReducer;
    }

    @Override
    public void validateArguments(List<Expr> args)
    {
      // anything goes
    }

    @Nullable
    @Override
    public ExpressionType getOutputType(Expr.InputBindingInspector inspector, List<Expr> args)
    {
      ExpressionType outputType = ExpressionType.LONG;
      for (Expr expr : args) {
        outputType = ExpressionTypeConversion.function(outputType, expr.getOutputType(inspector));
      }
      return outputType;
    }

    @Override
    public ExprEval apply(List<Expr> args, Expr.ObjectBinding bindings)
    {
      if (args.isEmpty()) {
        return ExprEval.of(null);
      }

      // evaluate arguments and collect output type
      List<ExprEval<?>> evals = new ArrayList<>();
      ExpressionType outputType = ExpressionType.LONG;

      for (Expr expr : args) {
        ExprEval<?> exprEval = expr.eval(bindings);
        ExpressionType exprType = exprEval.type();

        if (exprEval.value() != null) {
          if (isValidType(exprType)) {
            outputType = ExpressionTypeConversion.function(outputType, exprType);
          }
          evals.add(exprEval);
        }
      }

      if (evals.isEmpty()) {
        // The GREATEST/LEAST functions are not in the SQL standard. Emulate the behavior of postgres (return null if
        // all expressions are null, otherwise skip null values) since it is used as a base for a wide number of
        // databases. This also matches the behavior the long/double greatest/least post aggregators. Some other
        // databases (e.g., MySQL) return null if any expression is null.
        // https://www.postgresql.org/docs/9.5/functions-conditional.html
        // https://dev.mysql.com/doc/refman/8.0/en/comparison-operators.html#function_least
        return ExprEval.of(null);
      }

      switch (outputType.getType()) {
        case DOUBLE:
          //noinspection OptionalGetWithoutIsPresent (empty list handled earlier)
          return ExprEval.of(evals.stream().mapToDouble(ExprEval::asDouble).reduce(doubleReducer).getAsDouble());
        case LONG:
          //noinspection OptionalGetWithoutIsPresent (empty list handled earlier)
          return ExprEval.of(evals.stream().mapToLong(ExprEval::asLong).reduce(longReducer).getAsLong());
        default:
          //noinspection OptionalGetWithoutIsPresent (empty list handled earlier)
          return ExprEval.of(evals.stream().map(ExprEval::asString).reduce(stringReducer).get());
      }
    }

    private boolean isValidType(ExpressionType exprType)
    {
      switch (exprType.getType()) {
        case DOUBLE:
        case LONG:
        case STRING:
          return true;
        default:
          throw validationFailed("does not accept %s types", exprType);
      }
    }
  }

  // ------------------------------ implementations ------------------------------

  class ParseLong implements Function
  {
    @Override
    public String name()
    {
      return "parse_long";
    }

    @Override
    public void validateArguments(List<Expr> args)
    {
      validationHelperCheckAnyOfArgumentCount(args, 1, 2);
    }

    @Nullable
    @Override
    public ExpressionType getOutputType(Expr.InputBindingInspector inspector, List<Expr> args)
    {
      return ExpressionType.LONG;
    }

    @Override
    public ExprEval apply(List<Expr> args, Expr.ObjectBinding bindings)
    {
      final int radix = args.size() == 1 ? 10 : args.get(1).eval(bindings).asInt();

      final String input = args.get(0).eval(bindings).asString();
      if (input == null) {
        return ExprEval.ofLong(null);
      }

      final long retVal;
      try {
        if (radix == 16 && (input.startsWith("0x") || input.startsWith("0X"))) {
          // Strip leading 0x from hex strings.
          retVal = Long.parseLong(input.substring(2), radix);
        } else {
          retVal = Long.parseLong(input, radix);
        }
      }
      catch (NumberFormatException e) {
        return ExprEval.ofLong(null);
      }

      return ExprEval.of(retVal);
    }

    @Override
    public boolean canVectorize(Expr.InputBindingInspector inspector, List<Expr> args)
    {
      return (args.size() == 1 || (args.get(1).isLiteral() && args.get(1).getLiteralValue() instanceof Number)) &&
             inspector.canVectorize(args);
    }

    @Override
    public <T> ExprVectorProcessor<T> asVectorProcessor(Expr.VectorInputBindingInspector inspector, List<Expr> args)
    {
      if (args.size() == 1 || args.get(1).isLiteral()) {
        final int radix = args.size() == 1 ? 10 : ((Number) args.get(1).getLiteralValue()).intValue();
        return VectorProcessors.parseLong(inspector, args.get(0), radix);
      }
      // only single argument and 2 argument where the radix is constant is currently implemented
      // the canVectorize check should prevent this from happening, but explode just in case
      throw Exprs.cannotVectorize(this);
    }
  }

  class Pi implements Function
  {
    private static final double PI = Math.PI;

    @Override
    public String name()
    {
      return "pi";
    }

    @Override
    public ExprEval apply(List<Expr> args, Expr.ObjectBinding bindings)
    {
      return ExprEval.of(PI);
    }

    @Override
    public void validateArguments(List<Expr> args)
    {
      validationHelperCheckArgumentCount(args, 0);
    }

    @Nullable
    @Override
    public ExpressionType getOutputType(Expr.InputBindingInspector inspector, List<Expr> args)
    {
      return ExpressionType.DOUBLE;
    }

    @Override
    public boolean canVectorize(Expr.InputBindingInspector inspector, List<Expr> args)
    {
      return true;
    }

    @Override
    public <T> ExprVectorProcessor<T> asVectorProcessor(Expr.VectorInputBindingInspector inspector, List<Expr> args)
    {
      return VectorProcessors.constant(PI, inspector.getMaxVectorSize());
    }
  }

  class Abs extends UnivariateMathFunction
  {
    @Override
    public String name()
    {
      return "abs";
    }

    @Override
    protected ExprEval eval(long param)
    {
      return ExprEval.of(Math.abs(param));
    }

    @Override
    protected ExprEval eval(double param)
    {
      return ExprEval.of(Math.abs(param));
    }

    @Override
    public <T> ExprVectorProcessor<T> asVectorProcessor(Expr.VectorInputBindingInspector inspector, List<Expr> args)
    {
      return VectorMathProcessors.abs().asProcessor(inspector, args.get(0));
    }
  }

  class Acos extends DoubleUnivariateMathFunction
  {
    @Override
    public String name()
    {
      return "acos";
    }

    @Override
    protected ExprEval eval(double param)
    {
      return ExprEval.of(Math.acos(param));
    }

    @Override
    public <T> ExprVectorProcessor<T> asVectorProcessor(Expr.VectorInputBindingInspector inspector, List<Expr> args)
    {
      return VectorMathProcessors.acos().asProcessor(inspector, args.get(0));
    }
  }

  class Asin extends DoubleUnivariateMathFunction
  {
    @Override
    public String name()
    {
      return "asin";
    }

    @Override
    protected ExprEval eval(double param)
    {
      return ExprEval.of(Math.asin(param));
    }

    @Override
    public <T> ExprVectorProcessor<T> asVectorProcessor(Expr.VectorInputBindingInspector inspector, List<Expr> args)
    {
      return VectorMathProcessors.asin().asProcessor(inspector, args.get(0));
    }
  }

  class Atan extends DoubleUnivariateMathFunction
  {
    @Override
    public String name()
    {
      return "atan";
    }

    @Override
    protected ExprEval eval(double param)
    {
      return ExprEval.of(Math.atan(param));
    }

    @Override
    public <T> ExprVectorProcessor<T> asVectorProcessor(Expr.VectorInputBindingInspector inspector, List<Expr> args)
    {
      return VectorMathProcessors.atan().asProcessor(inspector, args.get(0));
    }
  }

  class BitwiseComplement extends UnivariateMathFunction
  {
    public static final String NAME = "bitwiseComplement";

    @Override
    public String name()
    {
      return NAME;
    }

    @Nullable
    @Override
    public ExpressionType getOutputType(Expr.InputBindingInspector inspector, List<Expr> args)
    {
      return ExpressionType.LONG;
    }

    @Override
    protected ExprEval eval(long param)
    {
      return ExprEval.of(~param);
    }

    @Override
    public <T> ExprVectorProcessor<T> asVectorProcessor(Expr.VectorInputBindingInspector inspector, List<Expr> args)
    {
      return VectorMathProcessors.bitwiseComplement().asProcessor(inspector, args.get(0));
    }
  }

  class BitwiseConvertLongBitsToDouble extends UnivariateMathFunction
  {
    @Override
    public String name()
    {
      return "bitwiseConvertLongBitsToDouble";
    }

    @Nullable
    @Override
    public ExpressionType getOutputType(Expr.InputBindingInspector inspector, List<Expr> args)
    {
      ExpressionType type = args.get(0).getOutputType(inspector);
      if (type == null) {
        return null;
      }
      return ExpressionType.DOUBLE;
    }

    @Override
    protected ExprEval eval(long param)
    {
      return ExprEval.of(Double.longBitsToDouble(param));
    }

    @Override
    public <T> ExprVectorProcessor<T> asVectorProcessor(Expr.VectorInputBindingInspector inspector, List<Expr> args)
    {
      return VectorMathProcessors.bitwiseConvertLongBitsToDouble().asProcessor(inspector, args.get(0));
    }
  }

  class BitwiseConvertDoubleToLongBits extends UnivariateMathFunction
  {
    @Override
    public String name()
    {
      return "bitwiseConvertDoubleToLongBits";
    }

    @Nullable
    @Override
    public ExpressionType getOutputType(Expr.InputBindingInspector inspector, List<Expr> args)
    {
      ExpressionType type = args.get(0).getOutputType(inspector);
      if (type == null) {
        return null;
      }
      return ExpressionType.LONG;
    }

    @Override
    protected ExprEval eval(double param)
    {
      return ExprEval.of(Double.doubleToLongBits(param));
    }

    @Override
    public <T> ExprVectorProcessor<T> asVectorProcessor(Expr.VectorInputBindingInspector inspector, List<Expr> args)
    {
      return VectorMathProcessors.bitwiseConvertDoubleToLongBits().asProcessor(inspector, args.get(0));
    }
  }

  class BitwiseAnd extends BivariateBitwiseMathFunction
  {
    @Override
    public String name()
    {
      return "bitwiseAnd";
    }

    @Override
    protected ExprEval eval(long x, long y)
    {
      return ExprEval.of(x & y);
    }

    @Override
    public <T> ExprVectorProcessor<T> asVectorProcessor(Expr.VectorInputBindingInspector inspector, List<Expr> args)
    {
      return VectorMathProcessors.bitwiseAnd().asProcessor(inspector, args.get(0), args.get(1));
    }
  }

  class BitwiseOr extends BivariateBitwiseMathFunction
  {
    @Override
    public String name()
    {
      return "bitwiseOr";
    }

    @Override
    protected ExprEval eval(long x, long y)
    {
      return ExprEval.of(x | y);
    }

    @Override
    public <T> ExprVectorProcessor<T> asVectorProcessor(Expr.VectorInputBindingInspector inspector, List<Expr> args)
    {
      return VectorMathProcessors.bitwiseOr().asProcessor(inspector, args.get(0), args.get(1));
    }
  }

  class BitwiseShiftLeft extends BivariateBitwiseMathFunction
  {
    @Override
    public String name()
    {
      return "bitwiseShiftLeft";
    }

    @Override
    protected ExprEval eval(long x, long y)
    {
      return ExprEval.of(x << y);
    }

    @Override
    public <T> ExprVectorProcessor<T> asVectorProcessor(Expr.VectorInputBindingInspector inspector, List<Expr> args)
    {
      return VectorMathProcessors.bitwiseShiftLeft().asProcessor(inspector, args.get(0), args.get(1));
    }
  }

  class BitwiseShiftRight extends BivariateBitwiseMathFunction
  {
    @Override
    public String name()
    {
      return "bitwiseShiftRight";
    }

    @Override
    protected ExprEval eval(long x, long y)
    {
      return ExprEval.of(x >> y);
    }

    @Override
    public <T> ExprVectorProcessor<T> asVectorProcessor(Expr.VectorInputBindingInspector inspector, List<Expr> args)
    {
      return VectorMathProcessors.bitwiseShiftRight().asProcessor(inspector, args.get(0), args.get(1));
    }
  }

  class BitwiseXor extends BivariateBitwiseMathFunction
  {
    @Override
    public String name()
    {
      return "bitwiseXor";
    }

    @Override
    protected ExprEval eval(long x, long y)
    {
      return ExprEval.of(x ^ y);
    }

    @Override
    public <T> ExprVectorProcessor<T> asVectorProcessor(Expr.VectorInputBindingInspector inspector, List<Expr> args)
    {
      return VectorMathProcessors.bitwiseXor().asProcessor(inspector, args.get(0), args.get(1));
    }
  }

  class Cbrt extends DoubleUnivariateMathFunction
  {
    @Override
    public String name()
    {
      return "cbrt";
    }

    @Override
    protected ExprEval eval(double param)
    {
      return ExprEval.of(Math.cbrt(param));
    }

    @Override
    public <T> ExprVectorProcessor<T> asVectorProcessor(Expr.VectorInputBindingInspector inspector, List<Expr> args)
    {
      return VectorMathProcessors.cbrt().asProcessor(inspector, args.get(0));
    }
  }

  class Ceil extends DoubleUnivariateMathFunction
  {
    @Override
    public String name()
    {
      return "ceil";
    }

    @Override
    protected ExprEval eval(double param)
    {
      return ExprEval.of(Math.ceil(param));
    }

    @Override
    public <T> ExprVectorProcessor<T> asVectorProcessor(Expr.VectorInputBindingInspector inspector, List<Expr> args)
    {
      return VectorMathProcessors.ceil().asProcessor(inspector, args.get(0));
    }
  }

  class Cos extends DoubleUnivariateMathFunction
  {
    @Override
    public String name()
    {
      return "cos";
    }

    @Override
    protected ExprEval eval(double param)
    {
      return ExprEval.of(Math.cos(param));
    }

    @Override
    public <T> ExprVectorProcessor<T> asVectorProcessor(Expr.VectorInputBindingInspector inspector, List<Expr> args)
    {
      return VectorMathProcessors.cos().asProcessor(inspector, args.get(0));
    }
  }

  class Cosh extends DoubleUnivariateMathFunction
  {
    @Override
    public String name()
    {
      return "cosh";
    }

    @Override
    protected ExprEval eval(double param)
    {
      return ExprEval.of(Math.cosh(param));
    }

    @Override
    public <T> ExprVectorProcessor<T> asVectorProcessor(Expr.VectorInputBindingInspector inspector, List<Expr> args)
    {
      return VectorMathProcessors.cosh().asProcessor(inspector, args.get(0));
    }
  }

  class Cot extends DoubleUnivariateMathFunction
  {
    @Override
    public String name()
    {
      return "cot";
    }

    @Override
    protected ExprEval eval(double param)
    {
      return ExprEval.of(Math.cos(param) / Math.sin(param));
    }

    @Override
    public <T> ExprVectorProcessor<T> asVectorProcessor(Expr.VectorInputBindingInspector inspector, List<Expr> args)
    {
      return VectorMathProcessors.cot().asProcessor(inspector, args.get(0));
    }
  }

  class SafeDivide extends BivariateMathFunction
  {
    public static final String NAME = "safe_divide";

    @Override
    public String name()
    {
      return NAME;
    }

    @Override
    public boolean canVectorize(Expr.InputBindingInspector inspector, List<Expr> args)
    {
      return false;
    }

    @Override
    protected ExprEval eval(final long x, final long y)
    {
      if (y == 0) {
        return ExprEval.ofLong(null);
      }
      return ExprEval.ofLong(x / y);
    }

    @Override
    protected ExprEval eval(final double x, final double y)
    {
      if (y == 0 || Double.isNaN(y)) {
        if (x != 0) {
          return ExprEval.ofDouble(null);
        }
        return ExprEval.ofDouble(0);
      }
      return ExprEval.ofDouble(x / y);
    }
  }

  class Div extends BivariateMathFunction
  {
    @Override
    public String name()
    {
      return "div";
    }

    @Override
    protected ExprEval eval(final long x, final long y)
    {
      return ExprEval.of(x / y);
    }

    @Override
    protected ExprEval eval(final double x, final double y)
    {
      return ExprEval.of((long) (x / y));
    }

    @Nullable
    @Override
    public ExpressionType getOutputType(Expr.InputBindingInspector inspector, List<Expr> args)
    {
      return ExpressionTypeConversion.integerMathFunction(
          args.get(0).getOutputType(inspector),
          args.get(1).getOutputType(inspector)
      );
    }

    @Override
    public <T> ExprVectorProcessor<T> asVectorProcessor(Expr.VectorInputBindingInspector inspector, List<Expr> args)
    {
      return VectorMathProcessors.longDivide().asProcessor(inspector, args.get(0), args.get(1));
    }
  }

  class Exp extends DoubleUnivariateMathFunction
  {
    @Override
    public String name()
    {
      return "exp";
    }

    @Override
    protected ExprEval eval(double param)
    {
      return ExprEval.of(Math.exp(param));
    }

    @Override
    public <T> ExprVectorProcessor<T> asVectorProcessor(Expr.VectorInputBindingInspector inspector, List<Expr> args)
    {
      return VectorMathProcessors.exp().asProcessor(inspector, args.get(0));
    }
  }

  class Expm1 extends DoubleUnivariateMathFunction
  {
    @Override
    public String name()
    {
      return "expm1";
    }

    @Override
    protected ExprEval eval(double param)
    {
      return ExprEval.of(Math.expm1(param));
    }

    @Override
    public <T> ExprVectorProcessor<T> asVectorProcessor(Expr.VectorInputBindingInspector inspector, List<Expr> args)
    {
      return VectorMathProcessors.expm1().asProcessor(inspector, args.get(0));
    }
  }

  class Floor extends DoubleUnivariateMathFunction
  {
    @Override
    public String name()
    {
      return "floor";
    }

    @Override
    protected ExprEval eval(double param)
    {
      return ExprEval.of(Math.floor(param));
    }

    @Override
    public <T> ExprVectorProcessor<T> asVectorProcessor(Expr.VectorInputBindingInspector inspector, List<Expr> args)
    {
      return VectorMathProcessors.floor().asProcessor(inspector, args.get(0));
    }
  }

  class GetExponent extends UnivariateMathFunction
  {
    @Override
    public String name()
    {
      return "getExponent";
    }

    @Override
    protected ExprEval eval(double param)
    {
      return ExprEval.of(Math.getExponent(param));
    }

    @Nullable
    @Override
    public ExpressionType getOutputType(Expr.InputBindingInspector inspector, List<Expr> args)
    {
      return ExpressionType.LONG;
    }

    @Override
    public <T> ExprVectorProcessor<T> asVectorProcessor(Expr.VectorInputBindingInspector inspector, List<Expr> args)
    {
      return VectorMathProcessors.getExponent().asProcessor(inspector, args.get(0));
    }
  }

  class Log extends DoubleUnivariateMathFunction
  {
    @Override
    public String name()
    {
      return "log";
    }

    @Override
    protected ExprEval eval(double param)
    {
      return ExprEval.of(Math.log(param));
    }

    @Override
    public <T> ExprVectorProcessor<T> asVectorProcessor(Expr.VectorInputBindingInspector inspector, List<Expr> args)
    {
      return VectorMathProcessors.log().asProcessor(inspector, args.get(0));
    }
  }

  class Log10 extends DoubleUnivariateMathFunction
  {
    @Override
    public String name()
    {
      return "log10";
    }

    @Override
    protected ExprEval eval(double param)
    {
      return ExprEval.of(Math.log10(param));
    }

    @Override
    public <T> ExprVectorProcessor<T> asVectorProcessor(Expr.VectorInputBindingInspector inspector, List<Expr> args)
    {
      return VectorMathProcessors.log10().asProcessor(inspector, args.get(0));
    }
  }

  class Log1p extends DoubleUnivariateMathFunction
  {
    @Override
    public String name()
    {
      return "log1p";
    }

    @Override
    protected ExprEval eval(double param)
    {
      return ExprEval.of(Math.log1p(param));
    }

    @Override
    public <T> ExprVectorProcessor<T> asVectorProcessor(Expr.VectorInputBindingInspector inspector, List<Expr> args)
    {
      return VectorMathProcessors.log1p().asProcessor(inspector, args.get(0));
    }
  }

  class NextUp extends DoubleUnivariateMathFunction
  {
    @Override
    public String name()
    {
      return "nextUp";
    }

    @Override
    protected ExprEval eval(double param)
    {
      return ExprEval.of(Math.nextUp(param));
    }

    @Override
    public <T> ExprVectorProcessor<T> asVectorProcessor(Expr.VectorInputBindingInspector inspector, List<Expr> args)
    {
      return VectorMathProcessors.nextUp().asProcessor(inspector, args.get(0));
    }
  }

  class Rint extends DoubleUnivariateMathFunction
  {
    @Override
    public String name()
    {
      return "rint";
    }

    @Override
    protected ExprEval eval(double param)
    {
      return ExprEval.of(Math.rint(param));
    }

    @Override
    public <T> ExprVectorProcessor<T> asVectorProcessor(Expr.VectorInputBindingInspector inspector, List<Expr> args)
    {
      return VectorMathProcessors.rint().asProcessor(inspector, args.get(0));
    }
  }

  class Round implements Function
  {
    //CHECKSTYLE.OFF: Regexp
    private static final BigDecimal MAX_FINITE_VALUE = BigDecimal.valueOf(Double.MAX_VALUE);
    private static final BigDecimal MIN_FINITE_VALUE = BigDecimal.valueOf(-1 * Double.MAX_VALUE);
    //CHECKSTYLE.ON: Regexp
    public static final String NAME = "round";

    @Override
    public String name()
    {
      return NAME;
    }

    @Override
    public ExprEval apply(List<Expr> args, Expr.ObjectBinding bindings)
    {
      ExprEval value1 = args.get(0).eval(bindings);

      if (value1.isNumericNull()) {
        return ExprEval.of(null);
      }

      if (!value1.type().anyOf(ExprType.LONG, ExprType.DOUBLE)) {
        throw validationFailed(
            "first argument should be a LONG or DOUBLE but got %s instead",
            value1.type()
        );
      }

      if (args.size() == 1) {
        return eval(value1);
      } else {
        ExprEval value2 = args.get(1).eval(bindings);
        if (!value2.type().is(ExprType.LONG)) {
          throw validationFailed(
              "second argument should be a LONG but got %s instead",
              value2.type()
          );
        }
        return eval(value1, value2.asInt());
      }
    }

    @Override
    public void validateArguments(List<Expr> args)
    {
      validationHelperCheckAnyOfArgumentCount(args, 1, 2);
    }

    @Nullable
    @Override
    public ExpressionType getOutputType(Expr.InputBindingInspector inspector, List<Expr> args)
    {
      return args.get(0).getOutputType(inspector);
    }

    private ExprEval eval(ExprEval param)
    {
      return eval(param, 0);
    }

    private ExprEval eval(ExprEval param, int scale)
    {
      if (param.type().is(ExprType.LONG)) {
        return ExprEval.of(BigDecimal.valueOf(param.asLong()).setScale(scale, RoundingMode.HALF_UP).longValue());
      } else if (param.type().is(ExprType.DOUBLE)) {
        BigDecimal decimal = safeGetFromDouble(param.asDouble());
        return ExprEval.of(decimal.setScale(scale, RoundingMode.HALF_UP).doubleValue());
      } else {
        return ExprEval.of(null);
      }
    }

    /**
     * Converts non-finite doubles to BigDecimal values instead of throwing a NumberFormatException.
     */
    private static BigDecimal safeGetFromDouble(double val)
    {
      if (Double.isNaN(val)) {
        return BigDecimal.ZERO;
      } else if (val == Double.POSITIVE_INFINITY) {
        return MAX_FINITE_VALUE;
      } else if (val == Double.NEGATIVE_INFINITY) {
        return MIN_FINITE_VALUE;
      }
      return BigDecimal.valueOf(val);
    }
  }

  class Signum extends DoubleUnivariateMathFunction
  {
    @Override
    public String name()
    {
      return "signum";
    }

    @Override
    protected ExprEval eval(double param)
    {
      return ExprEval.of(Math.signum(param));
    }

    @Override
    public <T> ExprVectorProcessor<T> asVectorProcessor(Expr.VectorInputBindingInspector inspector, List<Expr> args)
    {
      return VectorMathProcessors.signum().asProcessor(inspector, args.get(0));
    }
  }

  class Sin extends DoubleUnivariateMathFunction
  {
    @Override
    public String name()
    {
      return "sin";
    }

    @Override
    protected ExprEval eval(double param)
    {
      return ExprEval.of(Math.sin(param));
    }

    @Override
    public <T> ExprVectorProcessor<T> asVectorProcessor(Expr.VectorInputBindingInspector inspector, List<Expr> args)
    {
      return VectorMathProcessors.sin().asProcessor(inspector, args.get(0));
    }
  }

  class Sinh extends DoubleUnivariateMathFunction
  {
    @Override
    public String name()
    {
      return "sinh";
    }

    @Override
    protected ExprEval eval(double param)
    {
      return ExprEval.of(Math.sinh(param));
    }

    @Override
    public <T> ExprVectorProcessor<T> asVectorProcessor(Expr.VectorInputBindingInspector inspector, List<Expr> args)
    {
      return VectorMathProcessors.sinh().asProcessor(inspector, args.get(0));
    }
  }

  class Sqrt extends DoubleUnivariateMathFunction
  {
    @Override
    public String name()
    {
      return "sqrt";
    }

    @Override
    protected ExprEval eval(double param)
    {
      return ExprEval.of(Math.sqrt(param));
    }

    @Override
    public <T> ExprVectorProcessor<T> asVectorProcessor(Expr.VectorInputBindingInspector inspector, List<Expr> args)
    {
      return VectorMathProcessors.sqrt().asProcessor(inspector, args.get(0));
    }
  }

  class Tan extends DoubleUnivariateMathFunction
  {
    @Override
    public String name()
    {
      return "tan";
    }

    @Override
    protected ExprEval eval(double param)
    {
      return ExprEval.of(Math.tan(param));
    }

    @Override
    public <T> ExprVectorProcessor<T> asVectorProcessor(Expr.VectorInputBindingInspector inspector, List<Expr> args)
    {
      return VectorMathProcessors.tan().asProcessor(inspector, args.get(0));
    }
  }

  class Tanh extends DoubleUnivariateMathFunction
  {
    @Override
    public String name()
    {
      return "tanh";
    }

    @Override
    protected ExprEval eval(double param)
    {
      return ExprEval.of(Math.tanh(param));
    }

    @Override
    public <T> ExprVectorProcessor<T> asVectorProcessor(Expr.VectorInputBindingInspector inspector, List<Expr> args)
    {
      return VectorMathProcessors.tanh().asProcessor(inspector, args.get(0));
    }
  }

  class ToDegrees extends DoubleUnivariateMathFunction
  {
    @Override
    public String name()
    {
      return "toDegrees";
    }

    @Override
    protected ExprEval eval(double param)
    {
      return ExprEval.of(Math.toDegrees(param));
    }

    @Override
    public <T> ExprVectorProcessor<T> asVectorProcessor(Expr.VectorInputBindingInspector inspector, List<Expr> args)
    {
      return VectorMathProcessors.toDegrees().asProcessor(inspector, args.get(0));
    }
  }

  class ToRadians extends DoubleUnivariateMathFunction
  {
    @Override
    public String name()
    {
      return "toRadians";
    }

    @Override
    protected ExprEval eval(double param)
    {
      return ExprEval.of(Math.toRadians(param));
    }

    @Override
    public <T> ExprVectorProcessor<T> asVectorProcessor(Expr.VectorInputBindingInspector inspector, List<Expr> args)
    {
      return VectorMathProcessors.toRadians().asProcessor(inspector, args.get(0));
    }
  }

  class Ulp extends DoubleUnivariateMathFunction
  {
    @Override
    public String name()
    {
      return "ulp";
    }

    @Override
    protected ExprEval eval(double param)
    {
      return ExprEval.of(Math.ulp(param));
    }

    @Override
    public <T> ExprVectorProcessor<T> asVectorProcessor(Expr.VectorInputBindingInspector inspector, List<Expr> args)
    {
      return VectorMathProcessors.ulp().asProcessor(inspector, args.get(0));
    }
  }

  class Atan2 extends DoubleBivariateMathFunction
  {
    @Override
    public String name()
    {
      return "atan2";
    }

    @Override
    protected ExprEval eval(double y, double x)
    {
      return ExprEval.of(Math.atan2(y, x));
    }

    @Override
    public <T> ExprVectorProcessor<T> asVectorProcessor(Expr.VectorInputBindingInspector inspector, List<Expr> args)
    {
      return VectorMathProcessors.atan2().asProcessor(inspector, args.get(0), args.get(1));
    }
  }

  class CopySign extends DoubleBivariateMathFunction
  {
    @Override
    public String name()
    {
      return "copySign";
    }

    @Override
    protected ExprEval eval(double x, double y)
    {
      return ExprEval.of(Math.copySign(x, y));
    }

    @Override
    public <T> ExprVectorProcessor<T> asVectorProcessor(Expr.VectorInputBindingInspector inspector, List<Expr> args)
    {
      return VectorMathProcessors.copySign().asProcessor(inspector, args.get(0), args.get(1));
    }
  }

  class Hypot extends DoubleBivariateMathFunction
  {
    @Override
    public String name()
    {
      return "hypot";
    }

    @Override
    protected ExprEval eval(double x, double y)
    {
      return ExprEval.of(Math.hypot(x, y));
    }

    @Override
    public <T> ExprVectorProcessor<T> asVectorProcessor(Expr.VectorInputBindingInspector inspector, List<Expr> args)
    {
      return VectorMathProcessors.hypot().asProcessor(inspector, args.get(0), args.get(1));
    }
  }

  class Remainder extends DoubleBivariateMathFunction
  {
    @Override
    public String name()
    {
      return "remainder";
    }

    @Override
    protected ExprEval eval(double x, double y)
    {
      return ExprEval.of(Math.IEEEremainder(x, y));
    }

    @Override
    public <T> ExprVectorProcessor<T> asVectorProcessor(Expr.VectorInputBindingInspector inspector, List<Expr> args)
    {
      return VectorMathProcessors.remainder().asProcessor(inspector, args.get(0), args.get(1));
    }
  }

  class Max extends BivariateMathFunction
  {
    @Override
    public String name()
    {
      return "max";
    }

    @Override
    protected ExprEval eval(long x, long y)
    {
      return ExprEval.of(Math.max(x, y));
    }

    @Override
    protected ExprEval eval(double x, double y)
    {
      return ExprEval.of(Math.max(x, y));
    }

    @Override
    public <T> ExprVectorProcessor<T> asVectorProcessor(Expr.VectorInputBindingInspector inspector, List<Expr> args)
    {
      return VectorMathProcessors.max().asProcessor(inspector, args.get(0), args.get(1));
    }
  }

  class Min extends BivariateMathFunction
  {
    @Override
    public String name()
    {
      return "min";
    }

    @Override
    protected ExprEval eval(long x, long y)
    {
      return ExprEval.of(Math.min(x, y));
    }

    @Override
    protected ExprEval eval(double x, double y)
    {
      return ExprEval.of(Math.min(x, y));
    }

    @Override
    public <T> ExprVectorProcessor<T> asVectorProcessor(Expr.VectorInputBindingInspector inspector, List<Expr> args)
    {
      return VectorMathProcessors.min().asProcessor(inspector, args.get(0), args.get(1));
    }
  }

  class NextAfter extends DoubleBivariateMathFunction
  {
    @Override
    public String name()
    {
      return "nextAfter";
    }

    @Override
    protected ExprEval eval(double x, double y)
    {
      return ExprEval.of(Math.nextAfter(x, y));
    }

    @Override
    public <T> ExprVectorProcessor<T> asVectorProcessor(Expr.VectorInputBindingInspector inspector, List<Expr> args)
    {
      return VectorMathProcessors.nextAfter().asProcessor(inspector, args.get(0), args.get(1));
    }
  }

  class Pow extends DoubleBivariateMathFunction
  {
    @Override
    public String name()
    {
      return "pow";
    }

    @Override
    protected ExprEval eval(double x, double y)
    {
      return ExprEval.of(Math.pow(x, y));
    }

    @Override
    public <T> ExprVectorProcessor<T> asVectorProcessor(Expr.VectorInputBindingInspector inspector, List<Expr> args)
    {
      return VectorMathProcessors.doublePower().asProcessor(inspector, args.get(0), args.get(1));
    }
  }

  class Scalb extends BivariateFunction
  {
    @Override
    public String name()
    {
      return "scalb";
    }

    @Nullable
    @Override
    public ExpressionType getOutputType(Expr.InputBindingInspector inspector, List<Expr> args)
    {
      return ExpressionType.DOUBLE;
    }

    @Override
    protected ExprEval eval(ExprEval x, ExprEval y)
    {
      if (x.value() == null || y.value() == null) {
        return ExprEval.of(null);
      }

      ExpressionType type = ExpressionTypeConversion.autoDetect(x, y);
      switch (type.getType()) {
        case STRING:
          return ExprEval.of(null);
        default:
          return ExprEval.of(Math.scalb(x.asDouble(), y.asInt()));
      }
    }

    @Override
    public boolean canVectorize(Expr.InputBindingInspector inspector, List<Expr> args)
    {
      return inspector.areNumeric(args) && inspector.canVectorize(args);
    }

    @Override
    public <T> ExprVectorProcessor<T> asVectorProcessor(Expr.VectorInputBindingInspector inspector, List<Expr> args)
    {
      return VectorMathProcessors.scalb().asProcessor(inspector, args.get(0), args.get(1));
    }
  }

  class CastFunc extends BivariateFunction
  {
    @Override
    public String name()
    {
      return "cast";
    }

    @Override
    protected ExprEval eval(ExprEval x, ExprEval y)
    {
      if (x.value() == null) {
        return ExprEval.of(null);
      }
      ExpressionType castTo;
      try {
        castTo = ExpressionType.fromString(StringUtils.toUpperCase(y.asString()));
      }
      catch (IllegalArgumentException e) {
        throw validationFailed("Invalid type [%s]", y.asString());
      }
      return x.castTo(castTo);
    }

    @Override
    public Set<Expr> getScalarInputs(List<Expr> args)
    {
      if (args.get(1).isLiteral()) {
        ExpressionType castTo = ExpressionType.fromString(
            StringUtils.toUpperCase(args.get(1).getLiteralValue().toString())
        );
        switch (castTo.getType()) {
          case ARRAY:
            return Collections.emptySet();
          default:
            return ImmutableSet.of(args.get(0));
        }
      }
      // unknown cast, can't safely assume either way
      return Collections.emptySet();
    }

    @Override
    public Set<Expr> getArrayInputs(List<Expr> args)
    {
      if (args.get(1).isLiteral()) {
        ExpressionType castTo = ExpressionType.fromString(
            StringUtils.toUpperCase(args.get(1).getLiteralValue().toString())
        );
        switch (castTo.getType()) {
          case LONG:
          case DOUBLE:
          case STRING:
            return Collections.emptySet();
          default:
            return ImmutableSet.of(args.get(0));
        }
      }
      // unknown cast, can't safely assume either way
      return Collections.emptySet();
    }

    @Nullable
    @Override
    public ExpressionType getOutputType(Expr.InputBindingInspector inspector, List<Expr> args)
    {
      // can only know cast output type if cast to argument is constant
      if (args.get(1).isLiteral()) {
        return ExpressionType.fromString(StringUtils.toUpperCase(args.get(1).getLiteralValue().toString()));
      }
      return null;
    }

    @Override
    public boolean canVectorize(Expr.InputBindingInspector inspector, List<Expr> args)
    {
      return args.get(0).canVectorize(inspector) && args.get(1).isLiteral();
    }

    @Override
    public <T> ExprVectorProcessor<T> asVectorProcessor(Expr.VectorInputBindingInspector inspector, List<Expr> args)
    {
      return CastToTypeVectorProcessor.cast(
          args.get(0).asVectorProcessor(inspector),
          ExpressionType.fromString(StringUtils.toUpperCase(args.get(1).getLiteralValue().toString()))
      );
    }
  }

  class GreatestFunc extends ReduceFunction
  {
    public static final String NAME = "greatest";

    public GreatestFunc()
    {
      super(
          Math::max,
          Math::max,
          BinaryOperator.maxBy(Comparator.naturalOrder())
      );
    }

    @Override
    public String name()
    {
      return NAME;
    }
  }

  class LeastFunc extends ReduceFunction
  {
    public static final String NAME = "least";

    public LeastFunc()
    {
      super(
          Math::min,
          Math::min,
          BinaryOperator.minBy(Comparator.naturalOrder())
      );
    }

    @Override
    public String name()
    {
      return NAME;
    }
  }

  class ConditionFunc implements Function
  {
    @Override
    public String name()
    {
      return "if";
    }

    @Override
    public ExprEval apply(List<Expr> args, Expr.ObjectBinding bindings)
    {
      ExprEval x = args.get(0).eval(bindings);
      return x.asBoolean() ? args.get(1).eval(bindings) : args.get(2).eval(bindings);
    }

    @Override
    public void validateArguments(List<Expr> args)
    {
      validationHelperCheckArgumentCount(args, 3);
    }

    @Nullable
    @Override
    public ExpressionType getOutputType(Expr.InputBindingInspector inspector, List<Expr> args)
    {
      return ExpressionTypeConversion.conditional(inspector, args.subList(1, 3));
    }
  }

  /**
   * "Searched CASE" function, similar to {@code CASE WHEN boolean_expr THEN result [ELSE else_result] END} in SQL.
   */
  class CaseSearchedFunc implements Function
  {
    @Override
    public String name()
    {
      return "case_searched";
    }

    @Override
    public ExprEval apply(final List<Expr> args, final Expr.ObjectBinding bindings)
    {
      for (int i = 0; i < args.size(); i += 2) {
        if (i == args.size() - 1) {
          // ELSE else_result.
          return args.get(i).eval(bindings);
        } else if (args.get(i).eval(bindings).asBoolean()) {
          // Matching WHEN boolean_expr THEN result
          return args.get(i + 1).eval(bindings);
        }
      }

      return ExprEval.of(null);
    }

    @Override
    public void validateArguments(List<Expr> args)
    {
      validationHelperCheckMinArgumentCount(args, 2);
    }

    @Nullable
    @Override
    public ExpressionType getOutputType(Expr.InputBindingInspector inspector, List<Expr> args)
    {
      List<Expr> results = new ArrayList<>();
      for (int i = 1; i < args.size(); i += 2) {
        results.add(args.get(i));
      }
      // add else
      results.add(args.get(args.size() - 1));
      return ExpressionTypeConversion.conditional(inspector, results);
    }
  }

  /**
   * "Simple CASE" function, similar to {@code CASE expr WHEN value THEN result [ELSE else_result] END} in SQL.
   */
  class CaseSimpleFunc implements Function
  {
    @Override
    public String name()
    {
      return "case_simple";
    }

    @Override
    public ExprEval apply(final List<Expr> args, final Expr.ObjectBinding bindings)
    {
      for (int i = 1; i < args.size(); i += 2) {
        if (i == args.size() - 1) {
          // ELSE else_result.
          return args.get(i).eval(bindings);
        } else if (new BinEqExpr("==", args.get(0), args.get(i)).eval(bindings).asBoolean()) {
          // Matching WHEN value THEN result
          return args.get(i + 1).eval(bindings);
        }
      }

      return ExprEval.of(null);
    }

    @Override
    public void validateArguments(List<Expr> args)
    {
      validationHelperCheckMinArgumentCount(args, 3);
    }

    @Nullable
    @Override
    public ExpressionType getOutputType(Expr.InputBindingInspector inspector, List<Expr> args)
    {
      List<Expr> results = new ArrayList<>();
      for (int i = 2; i < args.size(); i += 2) {
        results.add(args.get(i));
      }
      // add else
      results.add(args.get(args.size() - 1));
      return ExpressionTypeConversion.conditional(inspector, results);
    }
  }

  /**
   * nvl is like coalesce, but accepts exactly two arguments.
   */
  class NvlFunc extends CoalesceFunc
  {
    @Override
    public String name()
    {
      return "nvl";
    }

    @Override
    public void validateArguments(List<Expr> args)
    {
      validationHelperCheckArgumentCount(args, 2);
    }
  }

  /**
   * SQL function "x IS NOT DISTINCT FROM y". Very similar to "x = y", i.e. {@link BinEqExpr}, except this function
   * never returns null, and this function considers NULL as a value, so NULL itself is not-distinct-from NULL. For
   * example: `x == null` returns `null` in SQL-compatible null handling mode, but `notdistinctfrom(x, null)` is
   * true if `x` is null.
   */
  class IsNotDistinctFromFunc implements Function
  {
    @Override
    public String name()
    {
      return "notdistinctfrom";
    }

    @Override
    public ExprEval apply(List<Expr> args, Expr.ObjectBinding bindings)
    {
      final ExprEval leftVal = args.get(0).eval(bindings);
      final ExprEval rightVal = args.get(1).eval(bindings);

      if (leftVal.value() == null || rightVal.value() == null) {
        return ExprEval.ofLongBoolean(leftVal.value() == null && rightVal.value() == null);
      }

      // Code copied and adapted from BinaryBooleanOpExprBase and BinEqExpr.
      // The code isn't shared due to differences in code structure: BinaryBooleanOpExprBase + BinEqExpr have logic
      // interleaved between parent and child class, but we can't use BinaryBooleanOpExprBase as a parent here, because
      // (a) this is a function, not an expr; and (b) our logic for handling and returning nulls is different from most
      // binary exprs, where null in means null out.
      final ExpressionType comparisonType = ExpressionTypeConversion.autoDetect(leftVal, rightVal);
      switch (comparisonType.getType()) {
        case STRING:
          return ExprEval.ofLongBoolean(Objects.equals(leftVal.asString(), rightVal.asString()));
        case LONG:
          return ExprEval.ofLongBoolean(leftVal.asLong() == rightVal.asLong());
        case ARRAY:
          final ExpressionType type = Preconditions.checkNotNull(
              ExpressionTypeConversion.leastRestrictiveType(leftVal.type(), rightVal.type()),
              "Cannot be null because ExprEval type is not nullable"
          );
          return ExprEval.ofLongBoolean(
              type.getNullableStrategy().compare(leftVal.castTo(type).asArray(), rightVal.castTo(type).asArray()) == 0
          );
        case DOUBLE:
        default:
          if (leftVal.isNumericNull() || rightVal.isNumericNull()) {
            return ExprEval.ofLongBoolean(leftVal.isNumericNull() && rightVal.isNumericNull());
          } else {
            return ExprEval.ofLongBoolean(leftVal.asDouble() == rightVal.asDouble());
          }
      }
    }

    @Override
    public void validateArguments(List<Expr> args)
    {
      validationHelperCheckArgumentCount(args, 2);
    }

    @Nullable
    @Override
    public ExpressionType getOutputType(Expr.InputBindingInspector inspector, List<Expr> args)
    {
      return ExpressionType.LONG;
    }
  }

  /**
   * SQL function "x IS DISTINCT FROM y". Very similar to "x <> y", i.e. {@link BinNeqExpr}, except this function
   * never returns null.
   *
   * Implemented as a subclass of IsNotDistinctFromFunc to keep the code simple, and because we expect "notdistinctfrom"
   * to be more common than "isdistinctfrom" in actual usage.
   */
  class IsDistinctFromFunc extends IsNotDistinctFromFunc
  {
    @Override
    public String name()
    {
      return "isdistinctfrom";
    }

    @Override
    public ExprEval apply(List<Expr> args, Expr.ObjectBinding bindings)
    {
      return ExprEval.ofLongBoolean(!super.apply(args, bindings).asBoolean());
    }

  }

  /**
   * SQL function "IS NOT FALSE". Different from "IS TRUE" in that it returns true for NULL as well.
   */
  class IsNotFalseFunc implements Function
  {
    @Override
    public String name()
    {
      return "notfalse";
    }

    @Override
    public ExprEval apply(List<Expr> args, Expr.ObjectBinding bindings)
    {
      final ExprEval arg = args.get(0).eval(bindings);
      return ExprEval.ofLongBoolean(arg.value() == null || arg.asBoolean());
    }

    @Override
    public void validateArguments(List<Expr> args)
    {
      validationHelperCheckArgumentCount(args, 1);
    }

    @Nullable
    @Override
    public ExpressionType getOutputType(Expr.InputBindingInspector inspector, List<Expr> args)
    {
      return ExpressionType.LONG;
    }
  }

  /**
   * SQL function "IS NOT TRUE". Different from "IS FALSE" in that it returns true for NULL as well.
   */
  class IsNotTrueFunc implements Function
  {
    @Override
    public String name()
    {
      return "nottrue";
    }

    @Override
    public ExprEval apply(List<Expr> args, Expr.ObjectBinding bindings)
    {
      final ExprEval arg = args.get(0).eval(bindings);
      return ExprEval.ofLongBoolean(arg.value() == null || !arg.asBoolean());
    }

    @Override
    public void validateArguments(List<Expr> args)
    {
      validationHelperCheckArgumentCount(args, 1);
    }

    @Nullable
    @Override
    public ExpressionType getOutputType(Expr.InputBindingInspector inspector, List<Expr> args)
    {
      return ExpressionType.LONG;
    }
  }

  /**
   * SQL function "IS FALSE".
   */
  class IsFalseFunc implements Function
  {
    @Override
    public String name()
    {
      return "isfalse";
    }

    @Override
    public ExprEval apply(List<Expr> args, Expr.ObjectBinding bindings)
    {
      final ExprEval arg = args.get(0).eval(bindings);
      return ExprEval.ofLongBoolean(arg.value() != null && !arg.asBoolean());
    }

    @Override
    public void validateArguments(List<Expr> args)
    {
      validationHelperCheckArgumentCount(args, 1);
    }

    @Nullable
    @Override
    public ExpressionType getOutputType(Expr.InputBindingInspector inspector, List<Expr> args)
    {
      return ExpressionType.LONG;
    }
  }

  /**
   * SQL function "IS TRUE".
   */
  class IsTrueFunc implements Function
  {
    @Override
    public String name()
    {
      return "istrue";
    }

    @Override
    public ExprEval apply(List<Expr> args, Expr.ObjectBinding bindings)
    {
      final ExprEval arg = args.get(0).eval(bindings);
      return ExprEval.ofLongBoolean(arg.asBoolean());
    }

    @Override
    public void validateArguments(List<Expr> args)
    {
      validationHelperCheckArgumentCount(args, 1);
    }

    @Nullable
    @Override
    public ExpressionType getOutputType(Expr.InputBindingInspector inspector, List<Expr> args)
    {
      return ExpressionType.LONG;
    }
  }

  class IsNullFunc implements Function
  {
    @Override
    public String name()
    {
      return "isnull";
    }

    @Override
    public ExprEval apply(List<Expr> args, Expr.ObjectBinding bindings)
    {
      final ExprEval expr = args.get(0).eval(bindings);
      return ExprEval.ofLongBoolean(expr.value() == null);
    }

    @Override
    public void validateArguments(List<Expr> args)
    {
      validationHelperCheckArgumentCount(args, 1);
    }

    @Nullable
    @Override
    public ExpressionType getOutputType(Expr.InputBindingInspector inspector, List<Expr> args)
    {
      return ExpressionType.LONG;
    }

    @Override
    public boolean canVectorize(Expr.InputBindingInspector inspector, List<Expr> args)
    {
      return args.get(0).canVectorize(inspector);
    }

    @Override
    public <T> ExprVectorProcessor<T> asVectorProcessor(Expr.VectorInputBindingInspector inspector, List<Expr> args)
    {
      return VectorProcessors.isNull(inspector, args.get(0));
    }
  }

  class IsNotNullFunc implements Function
  {
    @Override
    public String name()
    {
      return "notnull";
    }

    @Override
    public ExprEval apply(List<Expr> args, Expr.ObjectBinding bindings)
    {
      final ExprEval expr = args.get(0).eval(bindings);
      return ExprEval.ofLongBoolean(expr.value() != null);
    }

    @Override
    public void validateArguments(List<Expr> args)
    {
      validationHelperCheckArgumentCount(args, 1);
    }

    @Nullable
    @Override
    public ExpressionType getOutputType(Expr.InputBindingInspector inspector, List<Expr> args)
    {
      return ExpressionType.LONG;
    }


    @Override
    public boolean canVectorize(Expr.InputBindingInspector inspector, List<Expr> args)
    {
      return args.get(0).canVectorize(inspector);
    }

    @Override
    public <T> ExprVectorProcessor<T> asVectorProcessor(Expr.VectorInputBindingInspector inspector, List<Expr> args)
    {
      return VectorProcessors.isNotNull(inspector, args.get(0));
    }
  }

  class CoalesceFunc implements Function
  {
    @Override
    public String name()
    {
      return "coalesce";
    }

    @Override
    public ExprEval apply(List<Expr> args, Expr.ObjectBinding bindings)
    {
      for (int i = 0; i < args.size(); i++) {
        final Expr arg = args.get(i);
        final ExprEval<?> eval = arg.eval(bindings);
        if (i == args.size() - 1 || eval.value() != null) {
          return eval;
        }
      }

      throw DruidException.defensive("Not reached, argument count must be at least 1");
    }

    @Override
    public void validateArguments(List<Expr> args)
    {
      validationHelperCheckMinArgumentCount(args, 1);
    }

    @Nullable
    @Override
    public ExpressionType getOutputType(Expr.InputBindingInspector inspector, List<Expr> args)
    {
      return ExpressionTypeConversion.conditional(inspector, args);
    }

    @Override
    public boolean canVectorize(Expr.InputBindingInspector inspector, List<Expr> args)
    {
      return args.size() == 2 && inspector.canVectorize(args);
    }

    @Override
    public <T> ExprVectorProcessor<T> asVectorProcessor(Expr.VectorInputBindingInspector inspector, List<Expr> args)
    {
      return VectorConditionalProcessors.nvl(inspector, args.get(0), args.get(1));
    }
  }

  class ConcatFunc implements Function
  {
    @Override
    public String name()
    {
      return "concat";
    }

    @Override
    public ExprEval apply(List<Expr> args, Expr.ObjectBinding bindings)
    {
      if (args.size() == 0) {
        return ExprEval.of(null);
      } else {
        // Pass first argument in to the constructor to provide StringBuilder a little extra sizing hint.
        String first = args.get(0).eval(bindings).asString();
        if (first == null) {
          // Result of concatenation is null if any of the Values is null.
          // e.g. 'select CONCAT(null, "abc") as c;' will return null as per Standard SQL spec.
          return ExprEval.of(null);
        }
        final StringBuilder builder = new StringBuilder(first);
        for (int i = 1; i < args.size(); i++) {
          final String s = args.get(i).eval(bindings).asString();
          if (s == null) {
            // Result of concatenation is null if any of the Values is null.
            // e.g. 'select CONCAT(null, "abc") as c;' will return null as per Standard SQL spec.
            return ExprEval.of(null);
          } else {
            builder.append(s);
          }
        }
        return ExprEval.of(builder.toString());
      }
    }

    @Override
    public void validateArguments(List<Expr> args)
    {
      // anything goes
    }

    @Nullable
    @Override
    public ExpressionType getOutputType(Expr.InputBindingInspector inspector, List<Expr> args)
    {
      return ExpressionType.STRING;
    }

    @Override
    public boolean canVectorize(Expr.InputBindingInspector inspector, List<Expr> args)
    {
      return inspector.areScalar(args) && inspector.canVectorize(args);
    }

    @Override
    public <T> ExprVectorProcessor<T> asVectorProcessor(
        Expr.VectorInputBindingInspector inspector,
        List<Expr> args
    )
    {
      return VectorStringProcessors.concat(inspector, args);
    }
  }

  class StrlenFunc implements Function
  {
    @Override
    public String name()
    {
      return "strlen";
    }

    @Override
    public ExprEval apply(List<Expr> args, Expr.ObjectBinding bindings)
    {
      final String arg = args.get(0).eval(bindings).asString();
      return arg == null ? ExprEval.ofLong(null) : ExprEval.of(arg.length());
    }

    @Override
    public void validateArguments(List<Expr> args)
    {
      validationHelperCheckArgumentCount(args, 1);
    }

    @Nullable
    @Override
    public ExpressionType getOutputType(Expr.InputBindingInspector inspector, List<Expr> args)
    {
      return ExpressionType.LONG;
    }
  }

  class StringFormatFunc implements Function
  {
    @Override
    public String name()
    {
      return "format";
    }

    @Override
    public ExprEval apply(List<Expr> args, Expr.ObjectBinding bindings)
    {
      final String formatString = args.get(0).eval(bindings).asString();

      if (formatString == null) {
        return ExprEval.of(null);
      }

      final Object[] formatArgs = new Object[args.size() - 1];
      for (int i = 1; i < args.size(); i++) {
        formatArgs[i - 1] = args.get(i).eval(bindings).value();
      }

      return ExprEval.of(StringUtils.nonStrictFormat(formatString, formatArgs));
    }

    @Override
    public void validateArguments(List<Expr> args)
    {
      validationHelperCheckMinArgumentCount(args, 1);
    }

    @Nullable
    @Override
    public ExpressionType getOutputType(Expr.InputBindingInspector inspector, List<Expr> args)
    {
      return ExpressionType.STRING;
    }
  }

  class StrposFunc implements Function
  {
    @Override
    public String name()
    {
      return "strpos";
    }

    @Override
    public ExprEval apply(List<Expr> args, Expr.ObjectBinding bindings)
    {
      final String haystack = args.get(0).eval(bindings).asString();
      final String needle = args.get(1).eval(bindings).asString();

      if (haystack == null || needle == null) {
        return ExprEval.of(null);
      }

      final int fromIndex;

      if (args.size() >= 3) {
        fromIndex = args.get(2).eval(bindings).asInt();
      } else {
        fromIndex = 0;
      }

      return ExprEval.of(haystack.indexOf(needle, fromIndex));
    }

    @Override
    public void validateArguments(List<Expr> args)
    {
      validationHelperCheckAnyOfArgumentCount(args, 2, 3);
    }

    @Nullable
    @Override
    public ExpressionType getOutputType(Expr.InputBindingInspector inspector, List<Expr> args)
    {
      return ExpressionType.LONG;
    }
  }

  class SubstringFunc implements Function
  {
    @Override
    public String name()
    {
      return "substring";
    }

    @Override
    public ExprEval apply(List<Expr> args, Expr.ObjectBinding bindings)
    {
      final String arg = args.get(0).eval(bindings).asString();

      if (arg == null) {
        return ExprEval.of(null);
      }

      // Behaves like SubstringDimExtractionFn, not SQL SUBSTRING
      final int index = args.get(1).eval(bindings).asInt();
      final int length = args.get(2).eval(bindings).asInt();

      if (index < arg.length()) {
        if (length >= 0) {
          return ExprEval.of(arg.substring(index, Math.min(index + length, arg.length())));
        } else {
          return ExprEval.of(arg.substring(index));
        }
      } else {
        // this is a behavior mismatch with SQL SUBSTRING to be consistent with SubstringDimExtractionFn
        // In SQL, something like 'select substring("abc", 4,5) as c;' will return an empty string
        return ExprEval.of(null);
      }
    }

    @Override
    public void validateArguments(List<Expr> args)
    {
      validationHelperCheckArgumentCount(args, 3);
    }

    @Nullable
    @Override
    public ExpressionType getOutputType(Expr.InputBindingInspector inspector, List<Expr> args)
    {
      return ExpressionType.STRING;
    }
  }

  class RightFunc extends StringLongFunction
  {
    @Override
    public String name()
    {
      return "right";
    }

    @Nullable
    @Override
    public ExpressionType getOutputType(Expr.InputBindingInspector inspector, List<Expr> args)
    {
      return ExpressionType.STRING;
    }

    @Override
    protected ExprEval eval(String x, long y)
    {
      int yInt = (int) y;
      if (y < 0 || yInt != y) {
        throw validationFailed("needs a positive integer as the second argument");
      }
      int len = x.length();
      return ExprEval.of(y < len ? x.substring(len - yInt) : x);
    }
  }

  class LeftFunc extends StringLongFunction
  {
    @Override
    public String name()
    {
      return "left";
    }

    @Nullable
    @Override
    public ExpressionType getOutputType(Expr.InputBindingInspector inspector, List<Expr> args)
    {
      return ExpressionType.STRING;
    }

    @Override
    protected ExprEval eval(String x, long y)
    {
      int yInt = (int) y;
      if (yInt < 0 || yInt != y) {
        throw validationFailed("needs a positive integer as the second argument");
      }
      return ExprEval.of(y < x.length() ? x.substring(0, yInt) : x);
    }
  }

  class ReplaceFunc implements Function
  {
    @Override
    public String name()
    {
      return "replace";
    }

    @Override
    public ExprEval apply(List<Expr> args, Expr.ObjectBinding bindings)
    {
      final String arg = args.get(0).eval(bindings).asString();
      final String pattern = args.get(1).eval(bindings).asString();
      final String replacement = args.get(2).eval(bindings).asString();
      if (arg == null) {
        return ExprEval.of(null);
      }
      return ExprEval.of(StringUtils.replace(arg, pattern, replacement));
    }

    @Override
    public void validateArguments(List<Expr> args)
    {
      validationHelperCheckArgumentCount(args, 3);
    }

    @Nullable
    @Override
    public ExpressionType getOutputType(Expr.InputBindingInspector inspector, List<Expr> args)
    {
      return ExpressionType.STRING;
    }
  }

  class LowerFunc implements Function
  {
    @Override
    public String name()
    {
      return "lower";
    }

    @Override
    public ExprEval apply(List<Expr> args, Expr.ObjectBinding bindings)
    {
      final String arg = args.get(0).eval(bindings).asString();
      if (arg == null) {
        return ExprEval.of(null);
      }
      return ExprEval.of(StringUtils.toLowerCase(arg));
    }

    @Override
    public void validateArguments(List<Expr> args)
    {
      validationHelperCheckArgumentCount(args, 1);
    }

    @Nullable
    @Override
    public ExpressionType getOutputType(Expr.InputBindingInspector inspector, List<Expr> args)
    {
      return ExpressionType.STRING;
    }
  }

  class UpperFunc implements Function
  {
    @Override
    public String name()
    {
      return "upper";
    }

    @Override
    public ExprEval apply(List<Expr> args, Expr.ObjectBinding bindings)
    {
      final String arg = args.get(0).eval(bindings).asString();
      if (arg == null) {
        return ExprEval.of(null);
      }
      return ExprEval.of(StringUtils.toUpperCase(arg));
    }

    @Override
    public void validateArguments(List<Expr> args)
    {
      validationHelperCheckArgumentCount(args, 1);
    }

    @Nullable
    @Override
    public ExpressionType getOutputType(Expr.InputBindingInspector inspector, List<Expr> args)
    {
      return ExpressionType.STRING;
    }
  }

  class ReverseFunc extends UnivariateFunction
  {
    @Override
    public String name()
    {
      return "reverse";
    }

    @Nullable
    @Override
    public ExpressionType getOutputType(Expr.InputBindingInspector inspector, List<Expr> args)
    {
      return ExpressionType.STRING;
    }

    @Override
    protected ExprEval eval(ExprEval param)
    {
      if (!param.type().is(ExprType.STRING)) {
        throw validationFailed("needs a STRING argument but got %s instead", param.type());
      }
      final String arg = param.asString();
      return ExprEval.of(arg == null ? null : new StringBuilder(arg).reverse().toString());
    }
  }

  class RepeatFunc extends StringLongFunction
  {
    @Override
    public String name()
    {
      return "repeat";
    }

    @Nullable
    @Override
    public ExpressionType getOutputType(Expr.InputBindingInspector inspector, List<Expr> args)
    {
      return ExpressionType.STRING;
    }

    @Override
    protected ExprEval eval(String x, long y)
    {
      int yInt = (int) y;
      if (yInt != y) {
        throw validationFailed("needs an integer as the second argument");
      }
      return ExprEval.of(y < 1 ? null : StringUtils.repeat(x, yInt));
    }
  }

  class LpadFunc implements Function
  {
    @Override
    public String name()
    {
      return "lpad";
    }

    @Override
    public ExprEval apply(List<Expr> args, Expr.ObjectBinding bindings)
    {
      String base = args.get(0).eval(bindings).asString();
      int len = args.get(1).eval(bindings).asInt();
      String pad = args.get(2).eval(bindings).asString();

      if (base == null || pad == null) {
        return ExprEval.of(null);
      } else {
        return ExprEval.of(len == 0 ? null : StringUtils.lpad(base, len, pad));
      }

    }

    @Override
    public void validateArguments(List<Expr> args)
    {
      validationHelperCheckArgumentCount(args, 3);
    }

    @Nullable
    @Override
    public ExpressionType getOutputType(Expr.InputBindingInspector inspector, List<Expr> args)
    {
      return ExpressionType.STRING;
    }
  }

  class RpadFunc implements Function
  {
    @Override
    public String name()
    {
      return "rpad";
    }

    @Override
    public ExprEval apply(List<Expr> args, Expr.ObjectBinding bindings)
    {
      String base = args.get(0).eval(bindings).asString();
      int len = args.get(1).eval(bindings).asInt();
      String pad = args.get(2).eval(bindings).asString();

      if (base == null || pad == null) {
        return ExprEval.of(null);
      } else {
        return ExprEval.of(len == 0 ? null : StringUtils.rpad(base, len, pad));
      }

    }

    @Override
    public void validateArguments(List<Expr> args)
    {
      validationHelperCheckArgumentCount(args, 3);
    }

    @Nullable
    @Override
    public ExpressionType getOutputType(Expr.InputBindingInspector inspector, List<Expr> args)
    {
      return ExpressionType.STRING;
    }
  }

  class TimestampFromEpochFunc implements Function
  {
    @Override
    public String name()
    {
      return "timestamp";
    }

    @Override
    public ExprEval apply(List<Expr> args, Expr.ObjectBinding bindings)
    {
      ExprEval value = args.get(0).eval(bindings);
      if (!value.type().is(ExprType.STRING)) {
        throw validationFailed(
            "first argument should be a STRING but got %s instead",
            value.type()
        );
      }

      DateTimes.UtcFormatter formatter = DateTimes.ISO_DATE_OPTIONAL_TIME;
      if (args.size() > 1) {
        ExprEval format = args.get(1).eval(bindings);
        if (!format.type().is(ExprType.STRING)) {
          throw validationFailed(
              "second argument should be STRING but got %s instead",
              format.type()
          );
        }
        formatter = DateTimes.wrapFormatter(DateTimeFormat.forPattern(format.asString()));
      }
      DateTime date;
      try {
        date = formatter.parse(value.asString());
      }
      catch (IllegalArgumentException e) {
        throw validationFailed(e, "invalid value %s", value.asString());
      }
      return toValue(date);
    }

    @Override
    public void validateArguments(List<Expr> args)
    {
      validationHelperCheckAnyOfArgumentCount(args, 1, 2);
    }

    @Nullable
    @Override
    public ExpressionType getOutputType(Expr.InputBindingInspector inspector, List<Expr> args)
    {
      return ExpressionType.LONG;
    }

    protected ExprEval toValue(DateTime date)
    {
      return ExprEval.of(date.getMillis());
    }
  }

  class UnixTimestampFunc extends TimestampFromEpochFunc
  {
    @Override
    public String name()
    {
      return "unix_timestamp";
    }

    @Override
    protected final ExprEval toValue(DateTime date)
    {
      return ExprEval.of(date.getMillis() / 1000);
    }
  }

  class SubMonthFunc implements Function
  {
    @Override
    public String name()
    {
      return "subtract_months";
    }

    @Override
    public ExprEval apply(List<Expr> args, Expr.ObjectBinding bindings)
    {
      Long left = args.get(0).eval(bindings).asLong();
      Long right = args.get(1).eval(bindings).asLong();
      DateTimeZone timeZone = DateTimes.inferTzFromString(args.get(2).eval(bindings).asString());

      if (left == null || right == null) {
        return ExprEval.of(null);
      } else {
        return ExprEval.of(DateTimes.subMonths(right, left, timeZone));
      }

    }

    @Override
    public void validateArguments(List<Expr> args)
    {
      validationHelperCheckArgumentCount(args, 3);
    }

    @Nullable
    @Override
    public ExpressionType getOutputType(Expr.InputBindingInspector inspector, List<Expr> args)
    {
      return ExpressionType.LONG;
    }
  }

  class MultiValueStringToArrayFunction implements Function
  {
    @Override
    public String name()
    {
      return "mv_to_array";
    }

    @Override
    public ExprEval apply(List<Expr> args, Expr.ObjectBinding bindings)
    {
      return args.get(0).eval(bindings).castTo(ExpressionType.STRING_ARRAY);
    }

    @Override
    public void validateArguments(List<Expr> args)
    {
      validationHelperCheckArgumentCount(args, 1);
      IdentifierExpr expr = args.get(0).getIdentifierExprIfIdentifierExpr();

      if (expr == null) {
        throw validationFailed(
            "argument %s should be an identifier expression. Use array() instead",
            args.get(0).toString()
        );
      }
    }

    @Nullable
    @Override
    public ExpressionType getOutputType(Expr.InputBindingInspector inspector, List<Expr> args)
    {
      return ExpressionType.STRING_ARRAY;
    }

    @Override
    public boolean hasArrayInputs()
    {
      return true;
    }

    @Override
    public boolean hasArrayOutput()
    {
      return true;
    }

    @Override
    public Set<Expr> getScalarInputs(List<Expr> args)
    {
      return Collections.emptySet();
    }

    @Override
    public Set<Expr> getArrayInputs(List<Expr> args)
    {
      return ImmutableSet.copyOf(args);
    }
  }

  /**
   * Primarily internal helper function used to coerce null, [], and [null] into [null], similar to the logic done
   * by {@link org.apache.druid.segment.virtual.ExpressionSelectors#supplierFromDimensionSelector} when the 3rd
   * argument is true, which is done when implicitly mapping scalar functions over mvd values.
   *
   * Was formerly generated by the SQL layer for MV_CONTAINS and MV_OVERLAP, but is no longer generated, since the
   * SQL layer now prefers using {@link MvContainsFunction} and {@link MvOverlapFunction}. This function remains here
   * for backwards compatibility.
   */
  class MultiValueStringHarmonizeNullsFunction implements Function
  {
    @Override
    public String name()
    {
      return "mv_harmonize_nulls";
    }

    @Override
    public ExprEval apply(List<Expr> args, Expr.ObjectBinding bindings)
    {
      return harmonizeMultiValue(args.get(0).eval(bindings));
    }

    @Override
    public void validateArguments(List<Expr> args)
    {
      validationHelperCheckArgumentCount(args, 1);
    }

    @Nullable
    @Override
    public ExpressionType getOutputType(Expr.InputBindingInspector inspector, List<Expr> args)
    {
      return ExpressionType.STRING_ARRAY;
    }

    @Override
    public boolean hasArrayInputs()
    {
      return true;
    }

    @Override
    public boolean hasArrayOutput()
    {
      return true;
    }

    @Override
    public Set<Expr> getScalarInputs(List<Expr> args)
    {
      return Collections.emptySet();
    }

    @Override
    public Set<Expr> getArrayInputs(List<Expr> args)
    {
      return ImmutableSet.copyOf(args);
    }
  }

  class ArrayToMultiValueStringFunction implements Function
  {
    @Override
    public String name()
    {
      return "array_to_mv";
    }

    @Override
    public ExprEval apply(List<Expr> args, Expr.ObjectBinding bindings)
    {
      return args.get(0).eval(bindings).castTo(ExpressionType.STRING_ARRAY);
    }

    @Override
    public void validateArguments(List<Expr> args)
    {
      validationHelperCheckArgumentCount(args, 1);
    }

    @Nullable
    @Override
    public ExpressionType getOutputType(Expr.InputBindingInspector inspector, List<Expr> args)
    {
      return ExpressionType.STRING_ARRAY;
    }

    @Override
    public boolean hasArrayInputs()
    {
      return true;
    }

    @Override
    public boolean hasArrayOutput()
    {
      return true;
    }

    @Override
    public Set<Expr> getScalarInputs(List<Expr> args)
    {
      return Collections.emptySet();
    }

    @Override
    public Set<Expr> getArrayInputs(List<Expr> args)
    {
      return ImmutableSet.copyOf(args);
    }
  }

  class ArrayConstructorFunction implements Function
  {
    @Override
    public String name()
    {
      return "array";
    }

    @Override
    public ExprEval apply(List<Expr> args, Expr.ObjectBinding bindings)
    {
      final int length = args.size();
      if (length == 0) {
        return ExprEval.ofLongArray(ObjectArrays.EMPTY_ARRAY);
      }

      final ExprEval[] outEval = new ExprEval[length];
      for (int i = 0; i < length; i++) {
        outEval[i] = args.get(i).eval(bindings);
      }

      ExpressionType arrayElementType = null;

      // Try first to determine the element type, only considering nonnull values.
      for (final ExprEval<?> eval : outEval) {
        if (eval.value() != null) {
          arrayElementType = ExpressionTypeConversion.leastRestrictiveType(arrayElementType, eval.type());
        }
      }

      if (arrayElementType == null) {
        // Try again to determine the element type, this time considering nulls.
        for (final ExprEval<?> eval : outEval) {
          arrayElementType = ExpressionTypeConversion.leastRestrictiveType(arrayElementType, eval.type());
        }
      }

      final Object[] out = new Object[length];
      for (int i = 0; i < length; i++) {
        out[i] = outEval[i].castTo(arrayElementType).value();
      }
      return ExprEval.ofArray(ExpressionTypeFactory.getInstance().ofArray(arrayElementType), out);
    }

    @Override
    public boolean hasArrayOutput()
    {
      return true;
    }

    @Override
    public void validateArguments(List<Expr> args)
    {
      validationHelperCheckMinArgumentCount(args, 1);
    }

    @Nullable
    @Override
    public ExpressionType getOutputType(Expr.InputBindingInspector inspector, List<Expr> args)
    {
      ExpressionType type = null;
      for (Expr arg : args) {
        type = ExpressionTypeConversion.leastRestrictiveType(type, arg.getOutputType(inspector));
      }
      return type == null ? null : ExpressionTypeFactory.getInstance().ofArray(type);
    }
  }

  class ArrayLengthFunction implements Function
  {
    @Override
    public String name()
    {
      return "array_length";
    }

    @Override
    public ExprEval apply(List<Expr> args, Expr.ObjectBinding bindings)
    {
      final ExprEval expr = args.get(0).eval(bindings);
      final Object[] array = expr.asArray();
      if (array == null) {
        return ExprEval.of(null);
      }

      return ExprEval.ofLong(array.length);
    }


    @Override
    public Set<Expr> getArrayInputs(List<Expr> args)
    {
      return ImmutableSet.of(args.get(0));
    }

    @Override
    public boolean hasArrayInputs()
    {
      return true;
    }

    @Override
    public void validateArguments(List<Expr> args)
    {
      validationHelperCheckArgumentCount(args, 1);
    }

    @Nullable
    @Override
    public ExpressionType getOutputType(Expr.InputBindingInspector inspector, List<Expr> args)
    {
      return ExpressionType.LONG;
    }

    @Override
    public Set<Expr> getScalarInputs(List<Expr> args)
    {
      return Collections.emptySet();
    }
  }

  class StringToArrayFunction implements Function
  {
    @Override
    public String name()
    {
      return "string_to_array";
    }

    @Override
    public void validateArguments(List<Expr> args)
    {
      validationHelperCheckArgumentCount(args, 2);
    }

    @Nullable
    @Override
    public ExpressionType getOutputType(Expr.InputBindingInspector inspector, List<Expr> args)
    {
      return ExpressionType.STRING_ARRAY;
    }

    @Override
    public ExprEval apply(List<Expr> args, Expr.ObjectBinding bindings)
    {
      final ExprEval expr = args.get(0).eval(bindings);
      final String arrayString = expr.asString();
      if (arrayString == null) {
        return ExprEval.of(null);
      }

      final String split = args.get(1).eval(bindings).asString();
      return ExprEval.ofStringArray(arrayString.split(split != null ? split : ""));
    }

    @Override
    public boolean hasArrayOutput()
    {
      return true;
    }
  }

  class ArrayToStringFunction extends ArrayScalarFunction
  {
    @Override
    public String name()
    {
      return "array_to_string";
    }

    @Nullable
    @Override
    public ExpressionType getOutputType(Expr.InputBindingInspector inspector, List<Expr> args)
    {
      return ExpressionType.STRING;
    }

    @Override
    ExprEval doApply(ExprEval arrayExpr, ExprEval scalarExpr)
    {
      final String join = scalarExpr.asString();
      final Object[] raw = arrayExpr.asArray();
      if (raw == null || raw.length == 1 && raw[0] == null) {
        return ExprEval.of(null);
      }
      return ExprEval.of(
          Arrays.stream(raw).map(String::valueOf).collect(Collectors.joining(join != null ? join : ""))
      );
    }
  }

  class ArrayOffsetFunction extends ArrayScalarFunction
  {
    @Override
    public String name()
    {
      return "array_offset";
    }

    @Nullable
    @Override
    public ExpressionType getOutputType(Expr.InputBindingInspector inspector, List<Expr> args)
    {
      return ExpressionType.elementType(args.get(0).getOutputType(inspector));
    }

    @Override
    ExprEval doApply(ExprEval arrayExpr, ExprEval scalarExpr)
    {
      final Object[] array = arrayExpr.asArray();
      final int position = scalarExpr.asInt();

      if (array.length > position && position >= 0) {
        return ExprEval.ofType(arrayExpr.elementType(), array[position]);
      }
      return ExprEval.of(null);
    }
  }

  class ArrayOrdinalFunction extends ArrayScalarFunction
  {
    @Override
    public String name()
    {
      return "array_ordinal";
    }

    @Nullable
    @Override
    public ExpressionType getOutputType(Expr.InputBindingInspector inspector, List<Expr> args)
    {
      return ExpressionType.elementType(args.get(0).getOutputType(inspector));
    }

    @Override
    ExprEval doApply(ExprEval arrayExpr, ExprEval scalarExpr)
    {
      final Object[] array = arrayExpr.asArray();
      final int position = scalarExpr.asInt() - 1;

      if (array.length > position && position >= 0) {
        return ExprEval.ofType(arrayExpr.elementType(), array[position]);
      }
      return ExprEval.of(null);
    }
  }

  class ArrayOffsetOfFunction extends ArrayScalarFunction
  {
    @Override
    public String name()
    {
      return "array_offset_of";
    }

    @Nullable
    @Override
    public ExpressionType getOutputType(Expr.InputBindingInspector inspector, List<Expr> args)
    {
      return ExpressionType.LONG;
    }

    @Override
    ExprEval doApply(ExprEval arrayExpr, ExprEval scalarExpr)
    {
      final Object[] array = arrayExpr.asArray();

      switch (scalarExpr.type().getType()) {
        case STRING:
        case LONG:
        case DOUBLE:
          int index = -1;
          for (int i = 0; i < array.length; i++) {
            if (Objects.equals(array[i], scalarExpr.value())) {
              index = i;
              break;
            }
          }
          return index < 0 ? ExprEval.ofLong(null) : ExprEval.ofLong(index);
        default:
          throw validationFailed(
              "second argument must be a a scalar type but got %s instead",
              scalarExpr.type()
          );
      }
    }
  }

  class ArrayOrdinalOfFunction extends ArrayScalarFunction
  {
    @Override
    public String name()
    {
      return "array_ordinal_of";
    }

    @Nullable
    @Override
    public ExpressionType getOutputType(Expr.InputBindingInspector inspector, List<Expr> args)
    {
      return ExpressionType.LONG;
    }

    @Override
    ExprEval doApply(ExprEval arrayExpr, ExprEval scalarExpr)
    {
      final Object[] array = arrayExpr.asArray();
      switch (scalarExpr.type().getType()) {
        case STRING:
        case LONG:
        case DOUBLE:
          int index = -1;
          for (int i = 0; i < array.length; i++) {
            if (Objects.equals(array[i], scalarExpr.value())) {
              index = i;
              break;
            }
          }
          return index < 0
                 ? ExprEval.ofLong(null)
                 : ExprEval.ofLong(index + 1);
        default:
          throw validationFailed(
              "second argument must be a a scalar type but got %s instead",
              scalarExpr.type()
          );
      }
    }
  }

  class ScalarInArrayFunction extends ArrayScalarFunction
  {
    private static final int SCALAR_ARG = 0;
    private static final int ARRAY_ARG = 1;

    @Override
    public String name()
    {
      return "scalar_in_array";
    }

    @Nullable
    @Override
    public ExpressionType getOutputType(Expr.InputBindingInspector inspector, List<Expr> args)
    {
      return ExpressionType.LONG;
    }

    @Override
    Expr getScalarArgument(List<Expr> args)
    {
      return args.get(SCALAR_ARG);
    }

    @Override
    Expr getArrayArgument(List<Expr> args)
    {
      return args.get(ARRAY_ARG);
    }

    @Override
    ExprEval doApply(ExprEval arrayEval, ExprEval scalarEval)
    {
      final Object[] array = arrayEval.asArray();
      if (array == null) {
        return ExprEval.ofLong(null);
      }

      if (scalarEval.value() == null) {
        return arrayContainsNull(array) ? ExprEval.ofLongBoolean(true) : ExprEval.ofLong(null);
      }

      final ExpressionType matchType = arrayEval.elementType();
      final ExprEval<?> scalarEvalForComparison = ExprEval.castForEqualityComparison(scalarEval, matchType);

      if (scalarEvalForComparison == null) {
        return ExprEval.ofLongBoolean(false);
      } else {
        return ExprEval.ofLongBoolean(Arrays.asList(array).contains(scalarEvalForComparison.value()));
      }
    }

    @Override
    public Function asSingleThreaded(List<Expr> args, Expr.InputBindingInspector inspector)
    {
      if (args.get(ARRAY_ARG).isLiteral()) {
        final ExpressionType lhsType = args.get(SCALAR_ARG).getOutputType(inspector);
        if (lhsType == null) {
          return this;
        }

        final ExprEval<?> arrayEval = args.get(ARRAY_ARG).eval(InputBindings.nilBindings());
        final Object[] arrayValues = arrayEval.asArray();

        if (arrayValues == null) {
          return WithNullArray.INSTANCE;
        } else {
          final Set<Object> matchValues = new HashSet<>(Arrays.asList(arrayValues));
          final ExpressionType matchType = arrayEval.elementType();
          return new WithConstantArray(matchValues, matchType);
        }
      }
      return this;
    }

    /**
     * Specialization of {@link ScalarInArrayFunction} for null {@link #ARRAY_ARG}.
     */
    private static final class WithNullArray extends ScalarInArrayFunction
    {
      private static final WithNullArray INSTANCE = new WithNullArray();

      @Override
      public ExprEval apply(List<Expr> args, Expr.ObjectBinding bindings)
      {
        return ExprEval.of(null);
      }
    }

    /**
     * Specialization of {@link ScalarInArrayFunction} for constant, non-null {@link #ARRAY_ARG}.
     */
    private static final class WithConstantArray extends ScalarInArrayFunction
    {
      private final Set<Object> matchValues;
      private final ExpressionType matchType;

      public WithConstantArray(Set<Object> matchValues, ExpressionType matchType)
      {
        this.matchValues = Preconditions.checkNotNull(matchValues, "matchValues");
        this.matchType = Preconditions.checkNotNull(matchType, "matchType");
      }

      @Override
      public ExprEval apply(List<Expr> args, Expr.ObjectBinding bindings)
      {
        final ExprEval scalarEval = args.get(SCALAR_ARG).eval(bindings);

        if (scalarEval.value() == null) {
          return matchValues.contains(null) ? ExprEval.ofLongBoolean(true) : ExprEval.ofLong(null);
        }

        final ExprEval<?> scalarEvalForComparison = ExprEval.castForEqualityComparison(scalarEval, matchType);

        if (scalarEvalForComparison == null) {
          return ExprEval.ofLongBoolean(false);
        } else {
          return ExprEval.ofLongBoolean(matchValues.contains(scalarEvalForComparison.value()));
        }
      }
    }
  }

  class ArrayAppendFunction extends ArrayAddElementFunction
  {
    @Override
    public String name()
    {
      return "array_append";
    }

    @Override
    <T> Object[] add(TypeSignature<ExprType> elementType, T[] array, @Nullable T val)
    {
      final Object[] output = new Object[array.length + 1];
      for (int i = 0; i < array.length; i++) {
        output[i] = array[i];
      }
      output[array.length] = val;
      return output;
    }
  }

  class ArrayPrependFunction extends ArrayAddElementFunction
  {
    @Override
    public String name()
    {
      return "array_prepend";
    }

    @Override
    Expr getScalarArgument(List<Expr> args)
    {
      return args.get(0);
    }

    @Override
    Expr getArrayArgument(List<Expr> args)
    {
      return args.get(1);
    }

    @Override
    <T> Object[] add(TypeSignature<ExprType> elementType, T[] array, @Nullable T val)
    {
      final Object[] output = new Object[array.length + 1];
      output[0] = val;
      for (int i = 0; i < array.length; i++) {
        output[i + 1] = array[i];
      }
      return output;
    }
  }

  class ArrayConcatFunction extends ArraysMergeFunction
  {
    @Override
    public String name()
    {
      return "array_concat";
    }

    @Override
    <T> Object[] merge(TypeSignature<ExprType> elementType, T[] array1, T[] array2)
    {
      final Object[] output = new Object[array1.length + array2.length];
      for (int i = 0; i < array1.length; i++) {
        output[i] = array1[i];
      }
      for (int i = array1.length, j = 0; j < array2.length; i++, j++) {
        output[i] = array2[j];
      }
      return output;
    }
  }

  class ArraySetAddFunction extends ArrayAddElementFunction
  {
    @Override
    public String name()
    {
      return "array_set_add";
    }

    @Override
    <T> Object[] add(TypeSignature<ExprType> elementType, T[] array, @Nullable T val)
    {
      Set<T> set = new TreeSet<>(elementType.getNullableStrategy());
      set.addAll(Arrays.asList(array));
      set.add(val);
      return set.toArray();
    }
  }

  class ArraySetAddAllFunction extends ArraysMergeFunction
  {
    @Override
    public String name()
    {
      return "array_set_add_all";
    }

    @Override
    <T> Object[] merge(TypeSignature<ExprType> elementType, T[] array1, T[] array2)
    {
      Set<T> l = new TreeSet<>(elementType.getNullableStrategy());
      l.addAll(Arrays.asList(array1));
      l.addAll(Arrays.asList(array2));
      return l.toArray();
    }
  }

  class ArrayContainsFunction implements Function
  {
    @Override
    public String name()
    {
      return "array_contains";
    }

    @Override
    public boolean hasArrayOutput()
    {
      return true;
    }

    @Nullable
    @Override
    public ExpressionType getOutputType(Expr.InputBindingInspector inspector, List<Expr> args)
    {
      return ExpressionType.LONG;
    }

    @Override
    public ExprEval apply(List<Expr> args, Expr.ObjectBinding bindings)
    {
      final ExprEval lhsExpr = args.get(0).eval(bindings);
      final ExprEval rhsExpr = args.get(1).eval(bindings);

      final Object[] array1 = lhsExpr.asArray();
      if (array1 == null) {
        return ExprEval.ofLong(null);
      }
      ExpressionType array1Type = lhsExpr.asArrayType();

      if (rhsExpr.isArray()) {
        final Object[] array2 = rhsExpr.castTo(array1Type).asArray();
        if (array2 == null) {
          return ExprEval.ofLongBoolean(false);
        }
        return ExprEval.ofLongBoolean(Arrays.asList(array1).containsAll(Arrays.asList(array2)));
      } else {
        final Object elem = rhsExpr.castTo((ExpressionType) array1Type.getElementType()).value();
        if (elem == null && rhsExpr.value() != null) {
          return ExprEval.ofLongBoolean(false);
        }
        return ExprEval.ofLongBoolean(Arrays.asList(array1).contains(elem));
      }
    }

    @Override
    public void validateArguments(List<Expr> args)
    {
      validationHelperCheckArgumentCount(args, 2);
    }

    @Override
    public Set<Expr> getScalarInputs(List<Expr> args)
    {
      return Collections.emptySet();
    }

    @Override
    public Set<Expr> getArrayInputs(List<Expr> args)
    {
      return ImmutableSet.copyOf(args);
    }

    @Override
    public boolean hasArrayInputs()
    {
      return true;
    }

    @Override
    public Function asSingleThreaded(List<Expr> args, Expr.InputBindingInspector inspector)
    {
      if (args.get(1).isLiteral()) {
        final ExpressionType lhsType = args.get(0).getOutputType(inspector);
        if (lhsType == null || !(lhsType.isPrimitive() || lhsType.isPrimitiveArray())) {
          return this;
        }
        final ExpressionType lhsArrayType = ExpressionType.asArrayType(lhsType);
        final ExprEval<?> rhsEval = args.get(1).eval(InputBindings.nilBindings());
        if (rhsEval.isArray()) {
          final Object[] rhsArray = rhsEval.castTo(lhsArrayType).asArray();
          return new ContainsConstantArray(rhsArray);
        } else {
          final Object val = rhsEval.castTo((ExpressionType) lhsArrayType.getElementType()).value();
          return new ContainsConstantScalar(val);
        }
      }
      return this;
    }

    private static final class ContainsConstantArray extends ArrayContainsFunction
    {
      @Nullable
      final Object[] rhsArray;

      public ContainsConstantArray(@Nullable Object[] rhsArray)
      {
        this.rhsArray = rhsArray;
      }

      @Override
      public ExprEval apply(List<Expr> args, Expr.ObjectBinding bindings)
      {
        final ExprEval<?> lhsExpr = args.get(0).eval(bindings);
        final Object[] array1 = lhsExpr.asArray();
        if (array1 == null) {
          return ExprEval.ofLong(null);
        }
        if (rhsArray == null) {
          return ExprEval.ofLongBoolean(false);
        }
        return ExprEval.ofLongBoolean(Arrays.asList(array1).containsAll(Arrays.asList(rhsArray)));
      }
    }

    private static final class ContainsConstantScalar extends ArrayContainsFunction
    {
      @Nullable
      final Object val;

      public ContainsConstantScalar(@Nullable Object val)
      {
        this.val = val;
      }

      @Override
      public ExprEval apply(List<Expr> args, Expr.ObjectBinding bindings)
      {
        final ExprEval<?> lhsExpr = args.get(0).eval(bindings);

        final Object[] array1 = lhsExpr.asArray();
        if (array1 == null) {
          return ExprEval.ofLong(null);
        }
        return ExprEval.ofLongBoolean(Arrays.asList(array1).contains(val));
      }
    }
  }

  class ArrayOverlapFunction implements Function
  {
    @Override
    public String name()
    {
      return "array_overlap";
    }

    @Nullable
    @Override
    public ExpressionType getOutputType(Expr.InputBindingInspector inspector, List<Expr> args)
    {
      return ExpressionType.LONG;
    }

    @Override
    public ExprEval apply(List<Expr> args, Expr.ObjectBinding bindings)
    {
      final ExprEval arrayExpr1 = args.get(0).eval(bindings);
      final ExprEval arrayExpr2 = args.get(1).eval(bindings);

      final Object[] array1 = arrayExpr1.asArray();
      if (array1 == null) {
        return ExprEval.ofLong(null);
      }
      ExpressionType array1Type = arrayExpr1.asArrayType();
      final Object[] array2 = arrayExpr2.castTo(array1Type).asArray();
      if (array2 == null) {
        return ExprEval.ofLongBoolean(false);
      }
      List<Object> asList = Arrays.asList(array2);
      for (Object check : array1) {
        if (asList.contains(check)) {
          return ExprEval.ofLongBoolean(true);
        }
      }
      return ExprEval.ofLongBoolean(false);
    }

    @Override
    public void validateArguments(List<Expr> args)
    {
      validationHelperCheckArgumentCount(args, 2);
    }

    @Override
    public Set<Expr> getScalarInputs(List<Expr> args)
    {
      return Collections.emptySet();
    }

    @Override
    public Set<Expr> getArrayInputs(List<Expr> args)
    {
      return ImmutableSet.copyOf(args);
    }

    @Override
    public boolean hasArrayInputs()
    {
      return true;
    }

    @Override
    public Function asSingleThreaded(List<Expr> args, Expr.InputBindingInspector inspector)
    {
      if (args.get(1).isLiteral()) {
        final ExpressionType lhsType = args.get(0).getOutputType(inspector);
        if (lhsType == null || !(lhsType.isPrimitive() || lhsType.isPrimitiveArray())) {
          return this;
        }
        final ExpressionType lhsArrayType = ExpressionType.asArrayType(lhsType);
        final ExprEval<?> rhsEval = args.get(1).eval(InputBindings.nilBindings());
        final Object[] rhsArray = rhsEval.castTo(lhsArrayType).asArray();
        if (rhsArray == null) {
          return new ArrayOverlapFunction()
          {
            @Override
            public ExprEval apply(List<Expr> args, Expr.ObjectBinding bindings)
            {
              final ExprEval arrayExpr1 = args.get(0).eval(bindings);
              final Object[] array1 = arrayExpr1.asArray();
              if (array1 == null) {
                return ExprEval.ofLong(null);
              }
              return ExprEval.ofLongBoolean(false);
            }
          };
        }
        final Set<Object> set = new ObjectAVLTreeSet<>(lhsArrayType.getElementType().getNullableStrategy());
        set.addAll(Arrays.asList(rhsArray));
        return new OverlapConstantArray(set);
      }
      return this;
    }

    private static final class OverlapConstantArray extends ArrayContainsFunction
    {
      final Set<Object> set;

      public OverlapConstantArray(Set<Object> set)
      {
        this.set = set;
      }

      @Override
      public ExprEval apply(List<Expr> args, Expr.ObjectBinding bindings)
      {
        final ExprEval<?> lhsExpr = args.get(0).eval(bindings);
        final Object[] array1 = lhsExpr.asArray();
        if (array1 == null) {
          return ExprEval.ofLong(null);
        }
        for (Object check : array1) {
          if (set.contains(check)) {
            return ExprEval.ofLongBoolean(true);
          }
        }
        return ExprEval.ofLongBoolean(false);
      }
    }
  }

  class MvOverlapFunction implements Function
  {
    @Override
    public String name()
    {
      return "mv_overlap";
    }

    @Nullable
    @Override
    public ExpressionType getOutputType(Expr.InputBindingInspector inspector, List<Expr> args)
    {
      return ExpressionType.LONG;
    }

    @Override
    public ExprEval apply(List<Expr> args, Expr.ObjectBinding bindings)
    {
      final ExprEval<?> arg1 = Function.harmonizeMultiValue(args.get(0).eval(bindings));
      final ExprEval<?> arg2 = args.get(1).eval(bindings);
      final Object[] array1 = arg1.asArray();
      final Object[] array2 = arg2.castTo(ExpressionType.STRING_ARRAY).asArray();

      // If the second argument is null, check if the first argument contains null.
      if (array2 == null) {
        return ExprEval.ofLongBoolean(arrayContainsNull(array1));
      }

      // If the second argument is empty array, return false regardless of first argument.
      if (array2.length == 0) {
        return ExprEval.ofLongBoolean(false);
      }

      // Check for overlap.
      final Set<Object> set2 = new ObjectOpenHashSet<>(array2);
      for (final Object check : array1) {
        if (set2.contains(check)) {
          return ExprEval.ofLongBoolean(true);
        }
      }

      // No overlap.
      if (!set2.contains(null) && arrayContainsNull(array1)) {
        return ExprEval.ofLong(null);
      } else {
        return ExprEval.ofLongBoolean(false);
      }
    }

    @Override
    public void validateArguments(List<Expr> args)
    {
      validationHelperCheckArgumentCount(args, 2);
    }

    @Override
    public Set<Expr> getScalarInputs(List<Expr> args)
    {
      return Collections.emptySet();
    }

    @Override
    public Set<Expr> getArrayInputs(List<Expr> args)
    {
      return ImmutableSet.copyOf(args);
    }

    @Override
    public boolean hasArrayInputs()
    {
      return true;
    }

    @Override
    public Function asSingleThreaded(List<Expr> args, Expr.InputBindingInspector inspector)
    {
      final Expr arg2 = args.get(1);

      if (arg2.isLiteral()) {
        final ExprEval<?> rhsEval = args.get(1).eval(InputBindings.nilBindings());
        final Object[] rhsArray = rhsEval.castTo(ExpressionType.STRING_ARRAY).asArray();

        if (rhsArray == null) {
          return new MvOverlapConstantNull();
        } else if (rhsArray.length == 0) {
          return new MvOverlapConstantEmpty();
        } else if (rhsEval.elementType().isPrimitive()) {
          return new MvOverlapConstantArray(
              new ObjectOpenHashSet<>(rhsArray),
              arrayContainsNull(rhsArray)
          );
        }
      }

      return this;
    }

    private static final class MvOverlapConstantNull extends MvOverlapFunction
    {
      @Override
      public ExprEval<?> apply(List<Expr> args, Expr.ObjectBinding bindings)
      {
        final ExprEval<?> arrayExpr1 = Function.harmonizeMultiValue(args.get(0).eval(bindings));
        return ExprEval.ofLongBoolean(arrayContainsNull(arrayExpr1.asArray()));
      }
    }

    private static final class MvOverlapConstantEmpty extends MvOverlapFunction
    {
      @Override
      public ExprEval<?> apply(List<Expr> args, Expr.ObjectBinding bindings)
      {
        return ExprEval.ofLongBoolean(false);
      }
    }

    private static final class MvOverlapConstantArray extends MvOverlapFunction
    {
      final Set<Object> matchValues;
      final boolean rhsHasNull;

      public MvOverlapConstantArray(Set<Object> matchValues, boolean rhsHasNull)
      {
        this.matchValues = matchValues;
        this.rhsHasNull = rhsHasNull;
      }

      @Override
      public ExprEval<?> apply(List<Expr> args, Expr.ObjectBinding bindings)
      {
        final ExprEval<?> arrayExpr1 = Function.harmonizeMultiValue(args.get(0).eval(bindings));
        final Object[] array1 = arrayExpr1.asArray();

        for (final Object check : array1) {
          if (matchValues.contains(check)) {
            return ExprEval.ofLongBoolean(true);
          }
        }

        // No overlap.
        if (!rhsHasNull && arrayContainsNull(array1)) {
          return ExprEval.ofLong(null);
        } else {
          return ExprEval.ofLongBoolean(false);
        }
      }

      @Nullable
      @Override
      public BitmapColumnIndex asBitmapColumnIndex(ColumnIndexSelector selector, List<Expr> args)
      {
        final ColumnIndexSupplier arg0Supplier = args.get(0).asColumnIndexSupplier(selector, ColumnType.STRING);
        if (arg0Supplier == null) {
          return null;
        }
        final ValueSetIndexes values = arg0Supplier.as(ValueSetIndexes.class);
        if (values == null) {
          return null;
        }
        final List<?> sortedMatchValues = matchValues.stream()
                                                     .sorted(ColumnType.STRING.getNullableStrategy())
                                                     .collect(Collectors.toList());
        return values.forSortedValues(sortedMatchValues, ColumnType.STRING);
      }
    }
  }

  class MvContainsFunction implements Function
  {
    @Override
    public String name()
    {
      return "mv_contains";
    }

    @Nullable
    @Override
    public ExpressionType getOutputType(Expr.InputBindingInspector inspector, List<Expr> args)
    {
      return ExpressionType.LONG;
    }

    @Override
    public ExprEval apply(List<Expr> args, Expr.ObjectBinding bindings)
    {
      final ExprEval<?> arg1 = Function.harmonizeMultiValue(args.get(0).eval(bindings));
      final ExprEval<?> arg2 = args.get(1).eval(bindings);
      final Object[] array1 = arg1.asArray();
      final Object[] array2 = arg2.castTo(ExpressionType.STRING_ARRAY).asArray();

      // If the second argument is null, check if the first argument contains null.
      if (array2 == null) {
        return ExprEval.ofLongBoolean(arrayContainsNull(array1));
      }

      // If the second argument is an empty array, return true regardless of the first argument.
      if (array2.length == 0) {
        return ExprEval.ofLongBoolean(true);
      }

      return ExprEval.ofLongBoolean(Arrays.asList(array1).containsAll(Arrays.asList(array2)));
    }

    @Override
    public void validateArguments(List<Expr> args)
    {
      validationHelperCheckArgumentCount(args, 2);
    }

    @Override
    public Set<Expr> getScalarInputs(List<Expr> args)
    {
      return Collections.emptySet();
    }

    @Override
    public Set<Expr> getArrayInputs(List<Expr> args)
    {
      return ImmutableSet.copyOf(args);
    }

    @Override
    public boolean hasArrayInputs()
    {
      return true;
    }

    @Override
    public Function asSingleThreaded(List<Expr> args, Expr.InputBindingInspector inspector)
    {
      final Expr arg2 = args.get(1);

      if (arg2.isLiteral()) {
        final ExprEval<?> rhsEval = args.get(1).eval(InputBindings.nilBindings());
        final Object[] rhsArray = rhsEval.castTo(ExpressionType.STRING_ARRAY).asArray();

        if (rhsArray == null) {
          return new MvContainsConstantScalar(null);
        } else if (rhsArray.length == 0) {
          return new MvContainsConstantEmpty();
        } else if (rhsArray.length == 1) {
          return new MvContainsConstantScalar((String) rhsArray[0]);
        } else if (rhsEval.elementType().isPrimitive()) {
          return new MvContainsConstantArray(rhsArray);
        }
      }

      return this;
    }

    private static final class MvContainsConstantArray extends MvContainsFunction
    {
      private final List<Object> matchValues;

      public MvContainsConstantArray(final Object[] matchValues)
      {
        this.matchValues = Arrays.asList(matchValues);
      }

      @Override
      public ExprEval<?> apply(List<Expr> args, Expr.ObjectBinding bindings)
      {
        final ExprEval<?> arrayExpr1 = Function.harmonizeMultiValue(args.get(0).eval(bindings));
        final Object[] array1 = arrayExpr1.asArray();
        return ExprEval.ofLongBoolean(Arrays.asList(array1).containsAll(matchValues));
      }
    }

    private static final class MvContainsConstantScalar extends MvContainsFunction
    {
      @Nullable
      private final String matchValue;

      public MvContainsConstantScalar(@Nullable final String matchValue)
      {
        this.matchValue = matchValue;
      }

      @Override
      public ExprEval<?> apply(List<Expr> args, Expr.ObjectBinding bindings)
      {
        final ExprEval<?> arrayExpr1 = Function.harmonizeMultiValue(args.get(0).eval(bindings));
        final Object[] array1 = arrayExpr1.asArray();
        return ExprEval.ofLongBoolean(Arrays.asList(array1).contains(matchValue));
      }
    }

    private static final class MvContainsConstantEmpty extends MvContainsFunction
    {
      @Override
      public ExprEval<?> apply(List<Expr> args, Expr.ObjectBinding bindings)
      {
        return ExprEval.ofLongBoolean(true);
      }
    }
  }

  class ArraySliceFunction implements Function
  {
    @Override
    public String name()
    {
      return "array_slice";
    }

    @Override
    public void validateArguments(List<Expr> args)
    {
      validationHelperCheckAnyOfArgumentCount(args, 2, 3);
    }

    @Nullable
    @Override
    public ExpressionType getOutputType(Expr.InputBindingInspector inspector, List<Expr> args)
    {
      ExpressionType arrayType = args.get(0).getOutputType(inspector);
      return Optional.ofNullable(ExpressionType.asArrayType(arrayType)).orElse(arrayType);
    }

    @Override
    public Set<Expr> getScalarInputs(List<Expr> args)
    {
      if (args.size() == 3) {
        return ImmutableSet.of(args.get(1), args.get(2));
      } else {
        return ImmutableSet.of(args.get(1));
      }
    }

    @Override
    public Set<Expr> getArrayInputs(List<Expr> args)
    {
      return ImmutableSet.of(args.get(0));
    }

    @Override
    public boolean hasArrayInputs()
    {
      return true;
    }

    @Override
    public boolean hasArrayOutput()
    {
      return true;
    }

    @Override
    public ExprEval apply(List<Expr> args, Expr.ObjectBinding bindings)
    {
      final ExprEval expr = args.get(0).eval(bindings);
      final Object[] array = expr.asArray();
      if (array == null) {
        return ExprEval.of(null);
      }

      final int start = args.get(1).eval(bindings).asInt();
      int end = array.length;
      if (args.size() == 3) {
        end = args.get(2).eval(bindings).asInt();
      }

      if (start < 0 || start > array.length || start > end) {
        // Arrays.copyOfRange will throw exception in these cases
        return ExprEval.of(null);
      }

      return ExprEval.ofArray(expr.asArrayType(), Arrays.copyOfRange(expr.asArray(), start, end));
    }
  }

  abstract class SizeFormatFunc implements Function
  {
    protected abstract HumanReadableBytes.UnitSystem getUnitSystem();

    @Override
    public ExprEval apply(List<Expr> args, Expr.ObjectBinding bindings)
    {
      final ExprEval valueParam = args.get(0).eval(bindings);
      if (valueParam.isNumericNull()) {
        return ExprEval.of(null);
      }

      /**
       * only LONG and DOUBLE are allowed
       * For a DOUBLE, it will be cast to LONG before format
       */
      if (valueParam.value() != null && !valueParam.type().anyOf(ExprType.LONG, ExprType.DOUBLE)) {
        throw validationFailed(
            "needs a number as its first argument but got %s instead",
            valueParam.type()
        );
      }

      /**
       * By default, precision is 2
       */
      long precision = 2;
      if (args.size() > 1) {
        ExprEval precisionParam = args.get(1).eval(bindings);
        if (!precisionParam.type().is(ExprType.LONG)) {
          throw validationFailed(
              "needs a LONG as its second argument but got %s instead",
              precisionParam.type()
          );
        }
        precision = precisionParam.asLong();
        if (precision < 0 || precision > 3) {
          throw validationFailed(
              "given precision[%d] must be in the range of [0,3]",
              precision
          );
        }
      }

      return ExprEval.of(HumanReadableBytes.format(valueParam.asLong(), precision, this.getUnitSystem()));
    }

    @Override
    public void validateArguments(List<Expr> args)
    {
      validationHelperCheckAnyOfArgumentCount(args, 1, 2);
    }

    @Nullable
    @Override
    public ExpressionType getOutputType(Expr.InputBindingInspector inputTypes, List<Expr> args)
    {
      return ExpressionType.STRING;
    }
  }

  class HumanReadableDecimalByteFormatFunc extends SizeFormatFunc
  {
    @Override
    public String name()
    {
      return "human_readable_decimal_byte_format";
    }

    @Override
    protected HumanReadableBytes.UnitSystem getUnitSystem()
    {
      return HumanReadableBytes.UnitSystem.DECIMAL_BYTE;
    }
  }

  class HumanReadableBinaryByteFormatFunc extends SizeFormatFunc
  {
    @Override
    public String name()
    {
      return "human_readable_binary_byte_format";
    }

    @Override
    protected HumanReadableBytes.UnitSystem getUnitSystem()
    {
      return HumanReadableBytes.UnitSystem.BINARY_BYTE;
    }
  }

  class HumanReadableDecimalFormatFunc extends SizeFormatFunc
  {
    @Override
    public String name()
    {
      return "human_readable_decimal_format";
    }

    @Override
    protected HumanReadableBytes.UnitSystem getUnitSystem()
    {
      return HumanReadableBytes.UnitSystem.DECIMAL;
    }
  }

  /**
   * Harmonizes values for usage as multi-value-dimension-like inputs. The returned value is always of type
   * {@link ExpressionType#STRING_ARRAY}. Coerces null, [], and [null] into [null], similar to the logic done by
   * {@link org.apache.druid.segment.virtual.ExpressionSelectors#supplierFromDimensionSelector} when "homogenize"
   * is true.
   */
  private static ExprEval<?> harmonizeMultiValue(ExprEval<?> eval)
  {
    final ExprEval<?> castEval = eval.castTo(ExpressionType.STRING_ARRAY);
    if (castEval.value() == null || castEval.asArray().length == 0) {
      return ExprEval.ofArray(ExpressionType.STRING_ARRAY, new Object[]{null});
    }
    return castEval;
  }

  /**
   * Returns whether an array contains null.
   */
  private static boolean arrayContainsNull(Object[] array)
  {
    for (Object obj : array) {
      if (obj == null) {
        return true;
      }
    }
    return false;
  }
}
