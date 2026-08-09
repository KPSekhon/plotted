package app.plotted.identity.domain

import app.plotted.identity.persistence.UserRepository
import app.plotted.platform.spi.AccountDirectory
import org.springframework.stereotype.Component
import java.util.UUID

/** Identity's side of the [AccountDirectory] contract. */
@Component
class AccountDirectoryAdapter(
    private val users: UserRepository,
) : AccountDirectory {
    override fun exists(userId: UUID): Boolean = users.exists(userId)
}
