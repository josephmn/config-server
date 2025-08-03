@Library('utils') _  // Carga la biblioteca 'utils'

pipeline {
    agent any

    tools {
        jdk 'jdk17'
        maven 'maven3'
    }

    environment {
        NAME_APP = 'config-server-pro'
        CONTAINER_PORT = '8888'
        HOST_PORT = '8888'
        NETWORK = 'azure-net'
        GIT_CREDENTIALS = credentials('github-token')
        GIT_COMMITTER_NAME = 'josephmn'
        GIT_COMMITTER_EMAIL = 'josephcarlos.jcmn@gmail.com'
        NEW_VERSION = ''
        MAVEN_OPTS = '-Duser.timezone=UTC -Xmx1024m'
    }

    stages {
        stage('Setup Git Configuration') {
            steps {
                script {
                    echo "######################## : ======> CONFIGURANDO GIT..."

                    bat """
                        git config user.email "${GIT_COMMITTER_EMAIL}"
                        git config user.name "${GIT_COMMITTER_NAME}"
                        
                        REM Configurar remote con token para HTTPS
                        git remote set-url origin https://%GIT_CREDENTIALS%@github.com/josephmn/config-server.git
                        
                        REM Configurar Git para evitar problemas con CRLF
                        git config core.autocrlf true
                        git config core.longpaths true
                        
                        REM Configurar timeout para operaciones Git
                        git config http.postBuffer 524288000
                        git config http.timeout 300
                    """

                    // Verificar conexión
                    echo "Verificando conexión con repositorio..."
                    bat 'git remote -v'
                    bat 'git status'

                    // Probar conectividad
                    bat 'git fetch origin --dry-run'
                }
            }
        }

        stage('Compile Repository') {
            steps {
                script {
                    if (params.NOTIFICATION) {
                        notifyJob('JOB Jenkins', params.CORREO)
                    }
                    echo "######################## : ======> EJECUTANDO COMPILE..."
                    // Usar 'bat' para ejecutar comandos en Windows, para Linux usar 'sh'
                    bat 'mvn clean compile'
                }
            }
        }

        stage('Get Version') {
            steps {
                echo "######################## : ======> OBTENER ARCHIVO TXT VERSION..."
                script {
                    // Obtener la versión en Windows usando un archivo temporal
                    bat '''
                        mvn help:evaluate -Dexpression=project.version -q -DforceStdout > version.txt
                    '''
                    def version = readFile('version.txt').trim()
                    // Remover -SNAPSHOT si existe
                    version = version.replaceAll("-SNAPSHOT", "")
                    NEW_VERSION = version
                }
            }
        }

        stage('Merge RC into Main') {
            when {
                expression { params.MERGE_MAIN }
            }
            steps {
                echo "######################## : ======> CREANDO MERGE A RAMA MASTER..."
                script {
                    echo "=========> Git config..."
                    bat """
                        git config user.email "${GIT_COMMITTER_EMAIL}"
                        git config user.name "${GIT_COMMITTER_NAME}"
                    """

                    echo "=========> Pull a rama master..."
                    bat """
                        git checkout main
                        git pull origin main
                    """

                    if (params.FORCE_MERGE) {
                        echo "=========> Force merge de rama RC a rama main..."
                        bat """
                            git merge -X theirs ${RELEASE_TAG_NAME}
                        """
                        bat """
                            git push origin main
                        """
                    } else {
                        echo "=========> Merge de rama RC a rama main..."
                        bat """
                            git merge ${RELEASE_TAG_NAME}
                        """
                        bat """
                            git push origin main
                        """
                    }
                }
            }
        }

        stage('Generate Next Version') {
            when {
                expression { params.NEXT_VERSION }
            }
            steps {
                /*script {
                    echo "🧪 PROBANDO PERMISOS DEL TOKEN ACTUAL"

                    try {
                        // Test 1: Verificar acceso al repo
                        bat '''
                            echo "=== Test 1: Repo Access ==="
                            git ls-remote origin
                        '''
                        echo "✅ Acceso al repositorio: OK"

                        // Test 2: Probar push en seco
                        bat '''
                            echo "=== Test 2: Push Test ==="
                            git push origin develop --dry-run
                        '''
                        echo "✅ Permisos de push: OK"

                        // Test 3: Probar creación de tag
                        bat '''
                            echo "=== Test 3: Tag Test ==="
                            git tag test-tag-temp
                            git push origin test-tag-temp --dry-run
                            git tag -d test-tag-temp
                        '''
                        echo "✅ Permisos de tag: OK"

                        echo "🎉 TU TOKEN ACTUAL TIENE PERMISOS SUFICIENTES"

                    } catch (Exception e) {
                        echo "❌ Error detectado: ${e.getMessage()}"
                        echo "🔧 Necesitas actualizar los permisos del token"
                    }
                }*/
                script {
                    echo "🔍 VERIFICANDO RAMAS DISPONIBLES"

                    // Ver ramas locales
                    bat '''
                        echo "=== Ramas Locales ==="
                        git branch
                    '''

                    // Ver ramas remotas
                    bat '''
                        echo "=== Ramas Remotas ==="
                        git branch -r
                    '''

                    // Ver todas las ramas
                    bat '''
                        echo "=== Todas las Ramas ==="
                        git branch -a
                    '''

                    // Ver rama actual
                    bat '''
                        echo "=== Rama Actual ==="
                        git branch --show-current
                    '''
                }
            }

            /*steps {
                echo "######################## : ======> GENERANDO NUEVA VERSION SNAPSHOT..."
                script {
                    echo "=========> Git config..."
                    bat """
                        git config user.email "${GIT_COMMITTER_EMAIL}"
                        git config user.name "${GIT_COMMITTER_NAME}"
                        git config push.default simple
                    """

                    echo "=========> Generar siguiente SNAPSHOT..."
                    bat """
                        git checkout develop
                        git pull origin develop
                    """

                    // Limpiar archivos de release anteriores
                    echo "=========> Limpiando archivos de release anteriores..."
                    bat """
                        if exist release.properties del release.properties
                        if exist pom.xml.releaseBackup del pom.xml.releaseBackup
                    """

                    // mvn release:prepare -DreleaseVersion=1.1.0 -DdevelopmentVersion=1.1.1-SNAPSHOT -DautoVersionSubmodules=true -B

                    echo "=========> Ejecutando Maven Release Plugin: prepare con timeout..."
                    timeout(time: 10, unit: 'MINUTES') {
                        bat """
                            mvn release:prepare ^
                                -DautoVersionSubmodules=true ^
                                -Darguments="-DskipTests" ^
                                -DscmCommentPrefix="[jenkins-release] " ^
                                -DpushChanges=true ^
                                -DlocalCheckout=false ^
                                -DpreparationGoals="clean verify" ^
                                -B
                        """
                    }
                    echo "=========> Ejecutando Maven Release Plugin: perform con timeout..."
                    timeout(time: 15, unit: 'MINUTES') {
                        bat """
                            mvn release:perform ^
                                -Darguments="-DskipTests" ^
                                -DlocalCheckout=false ^
                                -B
                        """
                    }
                }
            }*/
        }

        stage('Build Application with Maven') {
            steps {
                echo "######################## : ======> EJECUTANDO BUILD APPLICATION MAVEN..."
                // Usar 'bat' para ejecutar comandos en Windows, para Linux usar 'sh'
                bat """
                    git checkout ${RELEASE_TAG_NAME}
                """

                bat 'mvn clean install'
            }
        }

        stage('Creating Network for Docker') {
            when {
                expression { params.DOCKER }
            }
            steps {
                echo "######################## : ======> EJECUTANDO CREACION DE RED PARA DOCKER: ${NETWORK}..."
                script {
                    def networkExists = bat(
                            script: "docker network ls | findstr ${NETWORK}",
                            returnStatus: true
                    )
                    if (networkExists != 0) {
                        echo "######################## : ======> La red '${NETWORK}' no existe. Creándola..."
                        bat "docker network create --attachable ${NETWORK}"
                    } else {
                        echo "######################## : ======> La red '${NETWORK}' ya existe. No es necesario crearla."
                    }
                }
            }
        }

        stage('Docker Build and Run') {
            when {
                expression { params.DOCKER }
            }
            steps {
                echo "######################## : ======> EJECUTANDO DOCKER BUILD AND RUN..."
                script {
                    echo "=========> VERSION RC: ${NEW_VERSION}"
                    echo "=========> APLICATIVO + VERSION: ${NAME_APP}:${NEW_VERSION}"
                    // Usar la versión capturada para los comandos Docker

                    // Verificar si existe el contenedor
                    def containerExists = bat(
                            script: "@docker ps -a --format '{{.Names}}' | findstr /i \"${NAME_APP}\"",
                            returnStatus: true
                    ) == 0

                    // Verificar si existe la imagen
                    def imageExists = bat(
                            script: "@docker images ${NAME_APP}:${NEW_VERSION} --format '{{.Repository}}:{{.Tag}}' | findstr /i \"${NAME_APP}:${NEW_VERSION}\"",
                            returnStatus: true
                    ) == 0

                    if (containerExists || imageExists) {
                        echo "=========> Se encontraron recursos existentes, procediendo a limpiarlos..."

                        if (containerExists) {
                            echo "=========> Eliminando contenedor existente: ${NAME_APP}"
                            bat "docker stop ${NAME_APP}"
                            bat "docker rm ${NAME_APP}"
                        }

                        if (imageExists) {
                            echo "=========> Eliminando imagen existente: ${NAME_APP}"
                            bat "docker rmi ${NAME_APP}:${NEW_VERSION}"
                        }
                    } else {
                        echo "=========> No se encontraron recursos existentes, procediendo con el despliegue..."
                    }

                    echo "=========> VERSION A DESPLEGAR: ${NEW_VERSION}"
                    echo "=========> APLICATIVO + VERSION: ${NAME_APP}:${NEW_VERSION}"

                    def name = NAME_APP.tokenize('-')[0..-2].join('-')
                    bat """
                        echo "=========> Construyendo nueva imagen con version ${NEW_VERSION}..."
                        docker build --build-arg NAME_APP=${name} --build-arg JAR_VERSION=${NEW_VERSION} -t ${NAME_APP}:${NEW_VERSION} .
                    """
                    bat """
                        echo "=========> Desplegando el contenedor: ${NAME_APP}..."
                        docker run -d --name ${NAME_APP} -p ${HOST_PORT}:${CONTAINER_PORT} --network=${NETWORK} ^
                        --env SERVER_PORT=${HOST_PORT} ^
                        ${NAME_APP}:${NEW_VERSION}
                    """
                }
            }
        }
    }

    post {
        always {
            // Limpiar archivos temporales de release
            script {
                bat """
                    if exist release.properties del release.properties
                    if exist pom.xml.releaseBackup del pom.xml.releaseBackup
                    if exist version.txt del version.txt
                """
            }
        }
        success {
            script {
                if (params.NOTIFICATION) {
                    notifyByMail('SUCCESS', params.CORREO)
                }
            }
        }
        failure {
            script {
                if (params.NOTIFICATION) {
                    notifyByMail('FAIL', params.CORREO)
                }
            }
        }
    }
}
