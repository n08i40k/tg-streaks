local dap = require("dap")

dap.adapters.python = {
  type = "server",
  host = "127.0.0.1",
  port = 5678,
}

dap.configurations.python = dap.configurations.python or {}
dap.configurations.python[#dap.configurations.python + 1] = {
  name = "Python Debugger: Remote Attach exteraGram",
  type = "python",
  request = "attach",
  connect = { host = "127.0.0.1", port = 5678 },
  pathMappings = {
    {
      localRoot = "/home/n08i40k/projects/tg-streaks/tg-streaks.py",
      remoteRoot = "/data/user/0/com.exteragram.messenger/files/plugins/tg-streaks.py",
    },
  },
  justMyCode = false,
}
