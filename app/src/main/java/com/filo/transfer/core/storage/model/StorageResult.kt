package com.filo.transfer.core.storage.model

/**
 * Type-safe result wrapper for storage operations.
 */
sealed interface StorageResult<out T> {
    data class Success<out T>(val data: T) : StorageResult<T>
    data class Failure(val error: StorageError) : StorageResult<Nothing>

    val isSuccess: Boolean
        get() = this is Success

    val isFailure: Boolean
        get() = this is Failure

    fun getOrNull(): T? = when (this) {
        is Success -> data
        is Failure -> null
    }

    fun errorOrNull(): StorageError? = when (this) {
        is Success -> null
        is Failure -> error
    }
}

inline fun <T, R> StorageResult<T>.map(transform: (T) -> R): StorageResult<R> = when (this) {
    is StorageResult.Success -> StorageResult.Success(transform(data))
    is StorageResult.Failure -> this
}

inline fun <T> StorageResult<T>.onSuccess(action: (T) -> Unit): StorageResult<T> {
    if (this is StorageResult.Success) action(data)
    return this
}

inline fun <T> StorageResult<T>.onFailure(action: (StorageError) -> Unit): StorageResult<T> {
    if (this is StorageResult.Failure) action(error)
    return this
}
