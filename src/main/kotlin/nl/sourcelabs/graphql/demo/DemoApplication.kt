package nl.sourcelabs.graphql.demo

import graphql.kickstart.execution.context.DefaultGraphQLContext
import graphql.kickstart.execution.context.GraphQLContext
import graphql.kickstart.execution.context.GraphQLContextBuilder
import graphql.kickstart.tools.GraphQLQueryResolver
import graphql.kickstart.tools.GraphQLResolver
import graphql.kickstart.tools.SchemaParser
import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLSchema
import graphql.servlet.GraphQLConfiguration
import graphql.servlet.SimpleGraphQLHttpServlet
import graphql.servlet.context.DefaultGraphQLServletContext
import graphql.servlet.context.DefaultGraphQLWebSocketContext
import graphql.servlet.context.GraphQLServletContextBuilder
import org.dataloader.BatchLoader
import org.dataloader.DataLoader
import org.dataloader.DataLoaderRegistry
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.boot.web.servlet.ServletRegistrationBean
import org.springframework.context.support.beans
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import javax.websocket.Session
import javax.websocket.server.HandshakeRequest

/**
 * Example Spring Boot application with GraphQL Java Tools + GraphQL Java Servlet using DataLoader.
 */
@SpringBootApplication
class DemoApplication

/**
 * Main for this Spring Boot application using Kotlin beans DSL.
 */
fun main(args: Array<String>) {
    runApplication<DemoApplication>(*args) {
        addInitializers(
                beans {
                    bean { Query() }
                    bean { OrderItemResolver() }
                    bean {
                        // Create a DataLoaderRegistry and register the DataLoader for the productBatchLoader.
                        DataLoaderRegistry().register(Product::class.simpleName, DataLoader.newDataLoader(ProductBatchLoader()))
                    }
                    bean {
                        // Create the GraphQLSchema
                        SchemaParser.newParser().file("schema.graphqls").resolvers(ref<Query>(), ref<OrderItemResolver>()).build().makeExecutableSchema()
                    }
                    bean {
                        // Register the GraphQLServlet
                        ServletRegistrationBean(ConfigurableGraphQLServlet(graphQLConfiguration(ref(), ref())), "/graphql")
                    }
                }
        )
    }
}

/**
 * Helper for the GraphQLConfiguration which registers the DataLoaderRegistry.
 *
 * GraphQLConfiguration allows for customizing how the runtime is behaving.
 */
fun graphQLConfiguration(schema: GraphQLSchema, registry: DataLoaderRegistry) = GraphQLConfiguration
        .with(schema)
        .with(DataLoaderAwareGraphQLContextBuilder(registry))
        .build()

/**
 * Data class for top level query field.
 */
data class Order(val items: List<OrderItem>)

/**
 * Data class for parent->child : order->orderItem relation.
 */
data class OrderItem(val productId: String)

/**
 * Data class for parent->child : orderItem->product relation.
 */
data class Product(val productId: String, val title: String)

/**
 * GraphQL java tools Query resolver.
 */
class Query : GraphQLQueryResolver {
    /**
     * Retrieve order data (dummy implementation).
     */
    fun order(): Order = Order(items = listOf(OrderItem("123"), OrderItem("234")))
}

/**
 * GraphQL java tools OrderItem field resolver.
 */
class OrderItemResolver() : GraphQLResolver<OrderItem> {

    /**
     * Delegates data fetching to the DataLoader for Product.
     */
    fun product(orderItem: OrderItem, env: DataFetchingEnvironment): CompletableFuture<Product> = env.getDataLoader<Product>().load(orderItem.productId)
}

/**
 * Customer batch loader for products.
 */
class ProductBatchLoader : BatchLoader<String, Product> {

    /**
     * Retrieve products by id in batch (dummy implementation).
     */
    fun getProducts(ids: List<String>) = ids.map { Product(productId = it, title = "title ${it}") }

    /**
     * Implementation of BatchLoader interface.
     */
    override fun load(ids: List<String>): CompletionStage<List<Product>> = CompletableFuture.supplyAsync { getProducts(ids) }
}

/**
 * DataLoaderAwareGraphQLContextBuilder allows for configuring the GraphQLContext with a given DataLoaderRegistry.
 */
class DataLoaderAwareGraphQLContextBuilder(private val registry: DataLoaderRegistry) : GraphQLContextBuilder, GraphQLServletContextBuilder {

    override fun build(): GraphQLContext {
        return DefaultGraphQLContext(registry, null)
    }

    override fun build(httpServletRequest: HttpServletRequest, httpServletResponse: HttpServletResponse): GraphQLContext {
        return DefaultGraphQLServletContext.createServletContext().with(httpServletRequest).with(httpServletResponse).with(registry).build()
    }

    override fun build(session: Session, handshakeRequest: HandshakeRequest): GraphQLContext {
        return DefaultGraphQLWebSocketContext.createWebSocketContext().with(session).with(handshakeRequest).with(registry).build()
    }
}

/**
 * GraphQLServlet that allows for configuring a DataLoaderRegistry.
 */
class ConfigurableGraphQLServlet(private val configuration: GraphQLConfiguration) : SimpleGraphQLHttpServlet() {
    override fun getConfiguration(): GraphQLConfiguration = configuration
}

/**
 * Kotlin extensions for fetching a DataLoader from the DataFetchingEnvironment in a standard way.
 */
private inline fun <reified T> DataFetchingEnvironment.getDataLoader(): DataLoader<String, T> {
    return dataLoaderRegistry.getDataLoader<String, T>(T::class.simpleName)
}
