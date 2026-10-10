package com.bulktext.gateway.cloud

sealed class CloudException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class NetworkException(message: String, cause: Throwable? = null) : CloudException(message, cause)
    class ConfigurationException(message: String) : CloudException(message)
    class InvalidPairingCodeException(message: String = "Pairing code is invalid or expired.") : CloudException(message)
    class AlreadyPairedException(message: String = "This phone installation is already paired. Disconnect the existing phone from the BulkText web app before pairing again.") : CloudException(message)
    class CredentialRejectedException(message: String = "Gateway credential was rejected. Re-pair this phone from the BulkText web app.") : CloudException(message)
    class QueueLeaseConflictException(message: String = "Cloud queue lease changed on the server.") : CloudException(message)
    class QueueBlockedException(message: String) : CloudException(message)
    class ServerContractException(message: String) : CloudException(message)
    class HttpException(val statusCode: Int, safeMessage: String) : CloudException(safeMessage)
}
