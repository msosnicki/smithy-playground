namespace playground.std
use smithy4s.api#UUID

@trait(selector: "service")
@protocolDefinition
structure stdlib {}

@stdlib
@documentation("A standard library service providing random generators of data.")
service Random {
  operations: [NextUUID]
}

@documentation("Generates a new UUID.")
operation NextUUID {
  output: NextUUIDOutput
}

structure NextUUIDOutput {
  @required
  value: UUID
}

@stdlib
@documentation("A standard library service providing time operations.")
service Clock {
  operations: [CurrentTimestamp]
}

@documentation("Provides the current time as a Timestamp.")
operation CurrentTimestamp {
  output: CurrentTimestampOutput
}

structure CurrentTimestampOutput {
  @required
  value: Timestamp
}
