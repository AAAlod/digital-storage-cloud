package dev.kehai.digitalstorage.storage;

final class InvalidStorageDataException extends IllegalStateException {
    InvalidStorageDataException(String message) { super(message); }
}
