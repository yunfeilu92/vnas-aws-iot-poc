# Fleet Indexing — required so Dynamic Thing Groups can query shadow fields.
resource "aws_iot_indexing_configuration" "fleet" {
  thing_indexing_configuration {
    thing_indexing_mode = "REGISTRY_AND_SHADOW"
  }
}
