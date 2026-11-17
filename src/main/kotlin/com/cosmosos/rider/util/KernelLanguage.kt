package com.cosmosos.rider.util

/** A language a kernel can be written in, and the `dotnet new` template that creates one. */
enum class KernelLanguage(
    val displayName: String,
    val projectExtension: String,
    val template: String,
    // The template to use when [template] is not installed. The templates
    // from before the split by language are a single C# one, cosmos-kernel.
    val fallbackTemplate: String?
) {
    // C# first: the default of a new project, and the project `cosmos build`
    // takes from a directory that holds both.
    CSHARP("C#", ".csproj", "cosmos-kernel-csharp", "cosmos-kernel"),
    VISUAL_BASIC("Visual Basic", ".vbproj", "cosmos-kernel-vb", null);

    /** The installed template that creates a kernel in this language, or null when none does. */
    fun findTemplate(installed: Set<String>): String? =
        listOfNotNull(template, fallbackTemplate).firstOrNull { it in installed }
}

/** The kernel templates of the Cosmos.Build.Templates package. */
object KernelTemplates {
    // What `dotnet new list` is asked for: every kernel template's short name starts with it.
    const val LIST_FILTER = "cosmos-kernel"

    // The row of dashes between the table's header and its templates.
    private val headerRule = Regex("""^-+(\s+-+)+\s*$""")

    private val shortName = Regex("""(?<=^|\s)cosmos-kernel(-[a-z]+)?(?=\s|$)""")

    /**
     * The short names of the kernel templates in the output of
     * `dotnet new list cosmos-kernel`. Only the table is read: the lines
     * above it, and the whole output when nothing is installed, name the
     * filter itself.
     */
    fun installed(listOutput: String?): Set<String> {
        val names = linkedSetOf<String>()
        var inTable = false
        for (line in listOutput.orEmpty().lines()) {
            if (!inTable) {
                inTable = headerRule.matches(line)
                continue
            }
            shortName.findAll(line).forEach { names += it.value }
        }
        return names
    }
}
