package org.axonframework.extensions.reactor.poc.uow;

import org.axonframework.messaging.MetaData;
import org.axonframework.messaging.unitofwork.UnitOfWork;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import java.util.Deque;
import java.util.function.Function;

/**
 * TODO DOC
 * @author Stefan Dragisic
 */
public abstract class ReactiveCurrentUnitOfWork {

    private ReactiveCurrentUnitOfWork() {
    }

    public static Mono<TransactionContext> currentContext() {
        return Mono.subscriberContext().handle((ctx, sink) -> {
            if (ctx.hasKey(TransactionContext.class)) {
                sink.next(ctx.get(TransactionContext.class));
            } else {
                sink.error(new IllegalStateException("No TransactionContext is currently found for this subscription."));
            }

            sink.complete();
        });
    }

    /**
     * Indicates whether a unit of work has already been started. This method can be used by interceptors to prevent
     * nesting of UnitOfWork instances.
     *
     * @return whether a UnitOfWork has already been started.
     */
    public static Mono<Boolean> isStarted() {
        return currentContext()
                .map(c -> !c.isEmpty());
    }

    /**
     * If a UnitOfWork is started, invokes the given {@code consumer} with the active Unit of Work. Otherwise,
     * it does nothing
     *
     * @param consumer The consumer to invoke if a Unit of Work is active
     * @return {@code true} if a unit of work is active, {@code false} otherwise
     */
    public static Mono<Boolean> ifStarted(Function<Mono<ReactiveUnitOfWork<?>>, Mono<?>> consumer) {
        return isStarted()
                .filter(Boolean::booleanValue)
                .flatMap(unused -> consumer.apply((Mono<ReactiveUnitOfWork<?>>) get()).thenReturn(Boolean.TRUE))
                .defaultIfEmpty(Boolean.FALSE);
    }

    /**
     * If a Unit of Work is started, execute the given {@code function} on it. Otherwise, returns an empty Optional.
     * Use this method when you wish to retrieve information from a Unit of Work, reverting to a default when no Unit
     * of Work is started.
     *
     * @param function The function to apply to the unit of work, if present
     * @param <T>      The type of return value expected
     * @return an optional containing the result of the function, or an empty Optional when no Unit of Work was started
     * @throws NullPointerException when a Unit of Work is present and the function returns null
     */
    public static <T> Mono<T> map(Function<ReactiveUnitOfWork<?>, T> function) {
        return isStarted().filter(Boolean::booleanValue).flatMap(unused -> get().map(function));
    }

    /**
     * Gets the UnitOfWork bound to the current thread. If no UnitOfWork has been started, an {@link
     * IllegalStateException} is thrown.
     * <p/>
     * To verify whether a UnitOfWork is already active, use {@link #isStarted()}.
     *
     * @return The UnitOfWork bound to the current thread.
     * @throws IllegalStateException if no UnitOfWork is active
     */
    public static Mono<? extends ReactiveUnitOfWork<?>> get() {
        return currentContext()
                .map(Deque::peek);

    }

    public static Mono<Boolean> isEmpty() {
        return currentContext()
                .map(Deque::isEmpty);
    }

    /**
     * Commits the current UnitOfWork. If no UnitOfWork was started, an {@link IllegalStateException} is thrown.
     *
     * @throws IllegalStateException if no UnitOfWork is currently started.
     * @see UnitOfWork#commit()
     */
    public static Mono<Void> commit() {
        return get().flatMap(ReactiveUnitOfWork::commit);
    }

    /**
     * Binds the given {@code unitOfWork} to the current context. If other UnitOfWork instances were bound, they
     * will be marked as inactive until the given UnitOfWork is cleared.
     *
     * @param unitOfWork The UnitOfWork to bind to the current thread.
     */
    public static Function<Context, Context> set(ReactiveUnitOfWork<?> unitOfWork) {
        return ctx -> {
            if (ctx.hasKey(TransactionContext.class)) {
                ctx.get(TransactionContext.class).push(unitOfWork);
                return ctx;
            }
            TransactionContext transactionContext = new TransactionContext();
            transactionContext.push(unitOfWork);
            return ctx.put(TransactionContext.class, transactionContext);
        };
    }

    /**
     * Creates execution context
     * todo
     */
    public static Function<Context, Context> initializeTransactionContext() {
        return ctx -> {
            if (ctx.hasKey(TransactionContext.class)) {
                ctx.delete(TransactionContext.class);
            }
            TransactionContext transactionContext = new TransactionContext();
            return ctx.put(TransactionContext.class, transactionContext);
        };
    }

    /**
     * Clears the UnitOfWork currently bound to the current thread, if that UnitOfWork is the given
     * {@code unitOfWork}.
     *
     * @param unitOfWork The UnitOfWork expected to be bound to the current thread.
     * @throws IllegalStateException when the given UnitOfWork was not the current active UnitOfWork. This exception
     *                               indicates a potentially wrong nesting of Units Of Work.
     */
    public static Mono<Void> clear(ReactiveUnitOfWork<?> unitOfWork) {
        return isStarted()
                .filter(Boolean::booleanValue)
                .flatMap(it -> currentContext())
                .doOnNext(deq -> {
                            if (deq.peek() == unitOfWork) {
                                deq.pop();
                            } else {
                                throw new IllegalStateException("Could not clear this UnitOfWork.It is not the active one.");
                            }
                        }
                )
                .then();
    }

    /**
     * Returns the Correlation Data attached to the current Unit of Work, or an empty {@link MetaData} instance
     * if no Unit of Work is started.
     *
     * @return a MetaData instance representing the current Unit of Work's correlation data, or an empty MetaData
     * instance if no Unit of Work is started.
     * @see UnitOfWork#getCorrelationData()
     */
    public static Mono<MetaData> correlationData() {
        return ReactiveCurrentUnitOfWork.map(ReactiveUnitOfWork::getCorrelationData);
    }

}
