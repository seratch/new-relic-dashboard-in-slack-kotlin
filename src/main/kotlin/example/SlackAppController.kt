package example

import com.github.seratch.jslack.lightning.App
import com.github.seratch.jslack.lightning.servlet.SlackAppServlet
import javax.servlet.annotation.WebServlet

@WebServlet("/slack/events")
class SlackAppController(app: App) : SlackAppServlet(app)