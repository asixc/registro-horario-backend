package com.workmeter.infrastructure.persistence

import com.workmeter.application.UnitOfWork
import com.workmeter.domain.Conflict
import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.enterprise.context.ApplicationScoped
import jakarta.persistence.OptimisticLockException
import org.hibernate.exception.ConstraintViolationException

@ApplicationScoped
class JpaUnitOfWork : UnitOfWork {
    override fun <T> execute(action: () -> T): T = try {
        QuarkusTransaction.joiningExisting().call<T> { action() }
    } catch (exception: RuntimeException) {
        val causes = generateSequence<Throwable>(exception) { it.cause }.take(15).toList()
        if (causes.any { it is OptimisticLockException ||
                it is ConstraintViolationException && it.sqlState in setOf("23505", "23P01", "23503") })
            throw Conflict("El cambio entra en conflicto con otro registro")
        throw exception
    }
}
