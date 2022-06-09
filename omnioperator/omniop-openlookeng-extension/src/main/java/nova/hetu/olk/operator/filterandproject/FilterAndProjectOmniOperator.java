/*
 * Copyright (C) 2020-2022. Huawei Technologies Co., Ltd. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package nova.hetu.olk.operator.filterandproject;

import com.google.common.collect.ImmutableList;
import io.airlift.units.DataSize;
import io.prestosql.memory.context.LocalMemoryContext;
import io.prestosql.operator.DriverContext;
import io.prestosql.operator.Operator;
import io.prestosql.operator.OperatorContext;
import io.prestosql.operator.OperatorFactory;
import io.prestosql.operator.project.PageProcessor;
import io.prestosql.spi.Page;
import io.prestosql.spi.plan.PlanNodeId;
import io.prestosql.spi.type.Type;
import nova.hetu.olk.operator.AbstractOmniOperatorFactory;
import nova.hetu.olk.tool.VecAllocatorHelper;
import nova.hetu.omniruntime.vector.VecAllocator;

import java.util.List;
import java.util.function.Supplier;

import static com.google.common.base.Preconditions.checkState;
import static io.prestosql.memory.context.AggregatedMemoryContext.newSimpleAggregatedMemoryContext;
import static java.util.Objects.requireNonNull;

public class FilterAndProjectOmniOperator
        implements Operator
{
    private final OperatorContext operatorContext;

    private final LocalMemoryContext pageProcessorMemoryContext;

    private final LocalMemoryContext outputMemoryContext;

    private final PageProcessor processor;

    private final OmniMergingPageOutput mergingOutput;

    private boolean finishing;

    public FilterAndProjectOmniOperator(OperatorContext operatorContext, PageProcessor processor,
                                        OmniMergingPageOutput mergingOutput)
    {
        this.processor = requireNonNull(processor, "processor is null");
        this.operatorContext = requireNonNull(operatorContext, "operatorContext is null");
        this.pageProcessorMemoryContext = newSimpleAggregatedMemoryContext()
                .newLocalMemoryContext(FilterAndProjectOmniOperator.class.getSimpleName());
        this.outputMemoryContext = operatorContext
                .newLocalSystemMemoryContext(FilterAndProjectOmniOperator.class.getSimpleName());
        this.mergingOutput = requireNonNull(mergingOutput, "mergingOutput is null");
    }

    @Override
    public OperatorContext getOperatorContext()
    {
        return operatorContext;
    }

    @Override
    public final void finish()
    {
        mergingOutput.finish();
        finishing = true;
    }

    @Override
    public final boolean isFinished()
    {
        boolean finished = finishing && mergingOutput.isFinished();
        if (finished) {
            outputMemoryContext.setBytes(mergingOutput.getRetainedSizeInBytes());
        }
        return finished;
    }

    @Override
    public final boolean needsInput()
    {
        return !finishing && mergingOutput.needsInput();
    }

    @Override
    public final void addInput(Page page)
    {
        checkState(!finishing, "Operator is already finishing");
        requireNonNull(page, "page is null");
        checkState(mergingOutput.needsInput(), "Page buffer is full");

        mergingOutput.addInput(processor.process(operatorContext.getSession().toConnectorSession(),
                operatorContext.getDriverContext().getYieldSignal(), pageProcessorMemoryContext, page));
        outputMemoryContext.setBytes(mergingOutput.getRetainedSizeInBytes() + pageProcessorMemoryContext.getBytes());
    }

    @Override
    public final Page getOutput()
    {
        return mergingOutput.getOutput();
    }

    @Override
    public void close() throws Exception
    {
        mergingOutput.close();
    }

    public static class FilterAndProjectOmniOperatorFactory
            extends AbstractOmniOperatorFactory
    {
        private final Supplier<PageProcessor> processor;

        private final List<Type> types;

        private final DataSize minOutputPageSize;

        private final int minOutputPageRowCount;

        private boolean closed;

        public FilterAndProjectOmniOperatorFactory(int operatorId, PlanNodeId planNodeId,
                                                   Supplier<PageProcessor> processor, List<Type> types, DataSize minOutputPageSize,
                                                   int minOutputPageRowCount, List<Type> sourceTypes)
        {
            this.operatorId = operatorId;
            this.planNodeId = requireNonNull(planNodeId, "planNodeId is null");
            this.processor = requireNonNull(processor, "processor is null");
            this.types = ImmutableList.copyOf(requireNonNull(types, "types is null"));
            this.minOutputPageSize = requireNonNull(minOutputPageSize, "minOutputPageSize is null");
            this.minOutputPageRowCount = minOutputPageRowCount;
            this.sourceTypes = sourceTypes;
            checkDataTypes(this.sourceTypes);
        }

        @Override
        public Operator createOperator(DriverContext driverContext)
        {
            checkState(!closed, "Factory is already closed");
            VecAllocator vecAllocator = VecAllocatorHelper.createOperatorLevelAllocator(driverContext,
                    VecAllocator.UNLIMIT, FilterAndProjectOmniOperator.class);
            OperatorContext operatorContext = driverContext.addOperatorContext(operatorId, planNodeId,
                    FilterAndProjectOmniOperator.class.getSimpleName());
            return new FilterAndProjectOmniOperator(operatorContext, processor.get(),
                    new OmniMergingPageOutput(types, minOutputPageSize.toBytes(), minOutputPageRowCount, vecAllocator, true));
        }

        @Override
        public void noMoreOperators()
        {
            closed = true;
        }

        @Override
        public OperatorFactory duplicate()
        {
            return new FilterAndProjectOmniOperatorFactory(operatorId, planNodeId, processor, types, minOutputPageSize,
                    minOutputPageRowCount, sourceTypes);
        }
    }
}
