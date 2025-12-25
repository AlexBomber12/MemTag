package com.alexbomber12.memtag.integrations.memento

class MementoResponseException(
    message: String,
) : RuntimeException(message)

class MementoSchemaException(
    message: String,
) : RuntimeException(message)

class MementoPagingException(
    message: String,
) : RuntimeException(message)
