variable "enable_services_stack" {
  type    = bool
  default = false
}

variable "service_layout" {
  type    = string
  default = "split"

  validation {
    condition     = contains(["split", "combined"], var.service_layout)
    error_message = "service_layout must be split or combined."
  }
}

variable "load_client_image_tag" {
  type    = string
  default = "unset"

  validation {
    condition     = !var.enable_services_stack || can(regex("^[0-9a-f]{40}$", var.load_client_image_tag))
    error_message = "With enable_services_stack the load_client_image_tag must be the 40-hex Git SHA of HEAD."
  }
}
