# Fixture overlay: session 2. The stack is on, the layout is combined, and the load-client tag is NOT here
# (it is the HEAD commit SHA, passed from a file outside the repository).
enable_services_stack = true
service_layout        = "combined"
enable_load_balancer  = true
enable_autoscaling    = true
enable_worker         = true
